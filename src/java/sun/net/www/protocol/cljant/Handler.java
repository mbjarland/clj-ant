package sun.net.www.protocol.cljant;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;

public class Handler extends URLStreamHandler {
  @Override
  protected URLConnection openConnection(URL url) {
    return new CljAntURLConnection(url);
  }

  @Override
  protected String toExternalForm(URL u) {
    return ""; //TODO: return something useful like clj-ant:calling-source-file.clj:line
  }

  static class CljAntURLConnection extends URLConnection {
    private String content;

    CljAntURLConnection(URL url) {
      super(url);
      this.content = url.getQuery();
    }

    void setContent(String content) {
      this.content = content;
    }

    @Override
    public void connect() {
      // do nothing
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
  }
}