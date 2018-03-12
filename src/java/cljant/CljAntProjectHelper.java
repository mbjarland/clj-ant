package cljant;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.ProjectHelperRepository;
import org.apache.tools.ant.helper.ProjectHelper2;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

public class CljAntProjectHelper extends ProjectHelper2 {
  public static final int MAGIC_CLJ_ANT = 2147483333;

  public static void register() {
    try {
      ProjectHelperRepository.getInstance().registerProjectHelper(CljAntProjectHelper.class);
    } catch (Exception e) {
      throw new BuildException(e);
    }
  }

  /**
   * Parse a source xml input.
   *
   * @param project the current project
   * @param source  the xml source
   * @throws BuildException if an error occurs
   */
  @Override
  public void parse(Project project, Object source) throws BuildException {
    Object result = source;

    if (source instanceof CljAntBuildFile) {
      CljAntBuildFile bf = (CljAntBuildFile) source;

      //project.log();
      project.setBaseDir(bf.getBaseDir());

      try {
        result = bf.toURL();
      } catch (MalformedURLException e) {
        throw new BuildException("cljant: failed to execute build:", e);
      }
    }

    super.parse(project, result);
  }
}