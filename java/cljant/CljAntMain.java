package cljant;

import org.apache.tools.ant.Main;

import java.util.Properties;

public class CljAntMain extends Main {
  private int exitCode = 1;

  public int startCljAnt(final String[] args, final Properties additionalUserProperties,
                         final ClassLoader coreLoader) {

    startAnt(args, additionalUserProperties, coreLoader);
    return exitCode;
  }

  @Override
  protected void exit(final int exitCode) {
    this.exitCode = exitCode;
  }
}
