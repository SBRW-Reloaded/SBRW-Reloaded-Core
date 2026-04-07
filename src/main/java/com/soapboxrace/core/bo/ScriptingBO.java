package com.soapboxrace.core.bo;

import com.oracle.truffle.js.scriptengine.GraalJSScriptEngine;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.slf4j.Logger;

import javax.annotation.PostConstruct;
import javax.ejb.Singleton;
import javax.ejb.Startup;
import javax.inject.Inject;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.util.Map;

/**
 * ScriptingBO is a bean that allows for the execution of JavaScript code.
 */
@Startup
@Singleton
public class ScriptingBO {

    @Inject
    private Logger logger;

    private ScriptEngine scriptEngine;

    @PostConstruct
    public void init() {
        try {
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
            Engine engine = Engine.newBuilder()
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();
            scriptEngine = GraalJSScriptEngine.create(engine,
                    Context.newBuilder("js")
                            .allowExperimentalOptions(true)
                            .allowHostAccess(HostAccess.ALL)
                            .allowHostClassLookup(s -> true)
                            .option("js.nashorn-compat", "true"));
            logger.info("Initialized GraalVM JavaScript engine with host access enabled");
        } catch (Exception e) {
            logger.warn("Failed to initialize GraalVM JS engine: {}", e.getMessage());
        }
    }

    /**
     * Evaluates JavaScript code and returns the result.
     *
     * @param script   The JavaScript code to evaluate.
     * @param bindings The bindings to pass to the script.
     * @return The result of the evaluation.
     * @throws ScriptException if an error occurs.
     */
    public Object eval(String script, Map<String, Object> bindings) throws ScriptException {
        if (scriptEngine == null) {
            throw new ScriptException("No JavaScript engine available");
        }
        return scriptEngine.eval(script, new SimpleBindings(bindings));
    }
}
