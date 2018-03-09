package cljant;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;

public class CljAntBuildFile extends File {
  private File baseDir;
  private String xmlData;

  public CljAntBuildFile(String pathname, String xmlData, File baseDir) {
    super(pathname);
    this.xmlData = xmlData;
    this.baseDir = baseDir;
  }

  public File getBaseDir() {
    return baseDir;
  }

  public void setBaseDir(File baseDir) {
    this.baseDir = baseDir;
  }

  public String getXmlData() {
    return xmlData;
  }

  public void setXmlData(String xmlData) {
    this.xmlData = xmlData;
  }

  @Override
  public URL toURL() throws MalformedURLException {
    return new URL("cljant://" + getName() + "?" + xmlData);
  }


  @Override
  public boolean exists() {
    return true;
  }

  @Override
  public boolean isDirectory() {
    return false;
  }

  @Override
  public boolean isFile() {
    return true;
  }
}
