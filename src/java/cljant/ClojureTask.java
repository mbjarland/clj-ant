/*
 * Bridge: an Ant Task whose execute() body lives in a Clojure IFn.
 *
 * This is the only Java in clj-ant. It exists because Ant calls
 *
 *     Class<?> klass = project.getTaskDefinitions().get(name);
 *     Task t = (Task) klass.getDeclaredConstructor().newInstance();
 *
 * inside Project.createTask, which means we can't register a
 * proxy/reify instance -- it has to be a real Class with a no-arg
 * constructor. Using gen-class would require AOT compilation;
 * runtime bytecode generation would mean an extra dependency
 * (insn / ASM). One small Java class is the lightest answer.
 *
 * The Clojure-side fn is looked up at execute() time via
 * getTaskName(), so a single ClojureTask class can serve any number
 * of distinct task names registered via clj-ant.core/deftask.
 */
package cljant;

import clojure.lang.IFn;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DynamicAttribute;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClojureTask extends Task implements DynamicAttribute {

  /** task-name -> Clojure body fn. Populated by clj-ant.core/deftask. */
  public static final Map<String, IFn> REGISTRY = new ConcurrentHashMap<>();

  private final Map<Keyword, String> attrs = new HashMap<>();
  private final StringBuilder        text  = new StringBuilder();

  public ClojureTask() { /* Ant requires a no-arg constructor. */ }

  /**
   * Ant calls us once per attribute on the element. We stash the raw
   * string -- ${...} expansion happens at execute() time once we have
   * the Project available.
   */
  @Override
  public void setDynamicAttribute(String name, String value) throws BuildException {
    attrs.put(Keyword.intern(null, name.toLowerCase()), value);
  }

  /** Discovered reflectively by IntrospectionHelper -- no @Override. */
  public void addText(String s) {
    text.append(s);
  }

  @Override
  public void execute() throws BuildException {
    String taskName = getTaskName();
    IFn body = REGISTRY.get(taskName);
    if (body == null) {
      throw new BuildException(
        "No Clojure body registered for task '" + taskName + "'");
    }

    // Apply property expansion now, then build the args map handed to
    // the Clojure fn.
    Project project = getProject();
    Map<Keyword, Object> args = new HashMap<>(attrs.size() + 3);
    for (Map.Entry<Keyword, String> e : attrs.entrySet()) {
      String raw = e.getValue();
      args.put(e.getKey(),
               project == null ? raw : project.replaceProperties(raw));
    }
    if (text.length() > 0) {
      String body_text = text.toString();
      args.put(Keyword.intern(null, "text"),
               project == null ? body_text
                               : project.replaceProperties(body_text));
    }
    args.put(Keyword.intern(null, "project"),   project);
    args.put(Keyword.intern(null, "task-name"), taskName);

    try {
      body.invoke(PersistentHashMap.create(args));
    } catch (BuildException be) {
      throw be;
    } catch (Throwable t) {
      throw new BuildException(
        "Clojure task '" + taskName + "' failed: " + t.getMessage(), t);
    }
  }
}
