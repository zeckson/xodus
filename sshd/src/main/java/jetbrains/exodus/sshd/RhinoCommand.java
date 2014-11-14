package jetbrains.exodus.sshd;

import jetbrains.exodus.entitystore.PersistentEntityStore;
import jetbrains.exodus.entitystore.StoreTransaction;
import jetbrains.exodus.entitystore.StoreTransactionalExecutable;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.jetbrains.annotations.NotNull;
import org.mozilla.javascript.*;
import org.mozilla.javascript.tools.ToolErrorReporter;

import java.awt.event.KeyEvent;
import java.io.*;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 *
 */
public class RhinoCommand implements Command, Runnable {

    private static final Log log = LogFactory.getLog(RhinoCommand.class);

    private static final String[] INITIAL_SCRIPTS = {"init.js", "functions.js", "opt.js"};
    private InputStreamReader in;
    private PrintStream out;
    private PrintStream err;
    private ExitCallback callback;
    private Scriptable scope;
    private volatile boolean stopped = false;
    private final Object lock = new Object();

    @NotNull
    private Map<String, Object> config;

    RhinoCommand(@NotNull Map<String, Object> config) {
        this.config = config;
    }

    @Override
    public void setInputStream(InputStream in) {
        try {
            this.in = new InputStreamReader(in, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setOutputStream(OutputStream out) {
        this.out = new PrintStream(out);
    }

    @Override
    public void setErrorStream(OutputStream err) {
        this.err = new PrintStream(err);
    }

    @Override
    public void setExitCallback(ExitCallback callback) {
        this.callback = callback;
    }

    @Override
    public void start(Environment env) throws IOException {
        new Thread(this).start();
    }

    @Override
    public void destroy() {
        stopped = true;
    }

    @Override
    public void run() {
        Context cx = Context.enter();
        cx.setClassShutter(new ClassShutterImpl());
        cx.setErrorReporter(new ErrorReporterImpl());
        try {
            processInput(cx);
        } finally {
            callback.onExit(0);
            synchronized (lock) {
                try {
                    if (scope != null) {
                        evalResourceScript(cx, scope, "destroy.js");
                        scope = null;
                    }
                } finally {
                    stopped = true;
                    Context.exit();
                }
            }
        }
    }

    private void println(String s) {
        out.print(s + "\n\r");
        out.flush();
    }

    private void println() {
        out.print("\n\r");
        out.flush();
    }

    private void print(char s) {
        out.print(s);
        out.flush();
    }

    private String readLine() {
        StringBuilder s = new StringBuilder();

        try {
            while (true) {
                char c = (char) in.read();

                if (c == -1 || c == 3) return null;
                if (c == '\n' || c == '\r') {
                    println();
                    break;
                }
                // delete
                if (c == 127 && s.length() > 0) {
                    s.deleteCharAt(s.length() - 1);
                    print('\b');
                }

                if (!isPrintableChar(c)) continue;

                print(c);
                s.append(c);
            }
            return s.toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isPrintableChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return (!Character.isISOControl(c)) &&
                c != KeyEvent.CHAR_UNDEFINED &&
                block != null &&
                block != Character.UnicodeBlock.SPECIALS;
    }

    private Scriptable createScope(Context cx) {
        final Scriptable scope = cx.initStandardObjects(null, true);
        NativeObject config = new NativeObject();
        for (Map.Entry<String, Object> entry : this.config.entrySet()) {
            config.defineProperty(entry.getKey(), entry.getValue(), NativeObject.READONLY);
        }
        config.defineProperty("implementationVersion", cx.getImplementationVersion(), NativeObject.READONLY);
        ScriptableObject.putProperty(scope, "config", config);
        ScriptableObject.putProperty(scope, "out", Context.javaToJS(out, scope));
        for (String name : INITIAL_SCRIPTS) {
            evalResourceScript(cx, scope, name);
        }
        return scope;
    }

    private void evalResourceScript(Context cx, Scriptable scope, String name) {
        try {
            cx.evaluateReader(scope, new InputStreamReader(this.getClass().getResourceAsStream(name)), name, 1, null);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void processInput(final Context cx) {
        while (true) {
            if (scope == null) {
                scope = createScope(cx);
            }

            out.flush();
            int lineno = 0;
            final StringBuilder source = new StringBuilder();

            // Collect lines of source to compile.
            while (true) {
                lineno++;
                print(lineno == 1 ? '>' : ' ');
                String newline = readLine();
                if (newline == null) return;
                source.append(newline).append("\n");
                if (cx.stringIsCompilableUnit(source.toString())) break;
            }

            final API api = new API();

            // execute script in transaction
            try {
                final Script script = cx.compileString(source.toString(), "<stdin>", lineno, null);
                if (script != null) {
                    long start = System.currentTimeMillis();

                    ScriptableObject.putProperty(scope, "api", Context.javaToJS(api, scope));
                    PersistentEntityStore entityStore = getPersistentStore(scope);
                    if (entityStore == null) {
                        processScript(script, cx, scope);
                    } else {
                        entityStore.executeInTransaction(new StoreTransactionalExecutable() {
                            @Override
                            public void execute(@NotNull StoreTransaction txn) {
                                ScriptableObject.putProperty(scope, "txn", Context.javaToJS(txn, scope));
                                processScript(script, cx, scope);
                            }
                        });
                    }

                    println("Complete in " + ((System.currentTimeMillis() - start)) + " ms");
                }
            } catch (RhinoException rex) {
                ToolErrorReporter.reportException(cx.getErrorReporter(), rex);
            } catch (VirtualMachineError ex) {
                // Treat StackOverflow and OutOfMemory as runtime errors
                ex.printStackTrace();
                Context.reportError(ex.toString());
            }

            if (api.reset) {
                synchronized (lock) {
                    evalResourceScript(cx, scope, "destroy.js");
                    scope = null;
                }
            }
        }
    }

    private PersistentEntityStore getPersistentStore(Scriptable scope) {
        Object store = scope.get("store", null);
        if (store == null || store == UniqueTag.NOT_FOUND) {
            return null;
        }
        return (PersistentEntityStore) Context.jsToJava(store, PersistentEntityStore.class);
    }

    protected void processScript(Script script, Context cx, Scriptable scope) {
        Object result = script.exec(cx, scope);
        if (result != Context.getUndefinedValue()) {
            println(Context.toString(result));
        }
    }

    public static class API {
        private boolean reset;

        public void reset() {
            reset = true;
        }

        @Override
        public String toString() {
            return "API";
        }
    }

    private class ErrorReporterImpl implements ErrorReporter {
        @Override
        public void error(String message, String sourceName, int line, String lineSource, int lineOffset) {
            StringBuilder s = new StringBuilder();
            if (line > 0) {
                s.append(line).append(": ");
            }
            s.append(message);
            println(s.toString());
        }

        @Override
        public EvaluatorException runtimeError(String message, String sourceName, int line, String lineSource, int lineOffset) {
            return new EvaluatorException(message, sourceName, line, lineSource, lineOffset);
        }

        @Override
        public void warning(String message, String sourceName, int line, String lineSource, int lineOffset) {
        }
    }

    private static Set<String> FORBIDDEN_CLASSES = new HashSet<String>() {{
        add("java.lang.System");
    }};

    private class ClassShutterImpl implements ClassShutter {

        @Override
        public boolean visibleToScripts(String fullClassName) {
            return !FORBIDDEN_CLASSES.contains(fullClassName);
        }
    }
}
