package me.lovelace.loveWebAdmin.managers;

import java.net.URI;
import java.util.*;
import me.lovelace.loveWebAdmin.database.ModerationStore;

/** Validates evidence without fetching links or trusting a client MIME/filename. */
public final class CaseEvidence {
  private CaseEvidence() {}

  public static int add(
      ModerationStore store, String kind, int id, String actor, Map<String, Object> body)
      throws java.sql.SQLException {
    String type = Objects.toString(body.get("type"), "TEXT"),
        value = Objects.toString(body.get("value"), "").trim(),
        visibility = Objects.toString(body.get("visibility"), "STAFF");
    byte[] data = null;
    String mime = null;
    if (type.equals("LINK")) {
      URI url = URI.create(value);
      if (!Set.of("https", "http").contains(url.getScheme())
          || url.getHost() == null
          || url.getUserInfo() != null)
        throw new IllegalArgumentException("Нужна обычная http/https-ссылка без учётных данных");
    }
    if (type.equals("IMAGE")) {
      String encoded = Objects.toString(body.get("base64"), "");
      if (encoded.length() > 933336)
        throw new IllegalArgumentException("Скриншот: не более 700 КБ");
      data = Base64.getDecoder().decode(encoded);
      mime = imageMime(data);
      value = value.replaceAll("[\\\\/\\p{Cntrl}]", "_");
      if (value.length() > 100) value = value.substring(0, 100);
      if (value.isBlank()) value = "Скриншот";
    }
    return store.addEvidence(kind, id, actor, type, value, visibility, mime, data);
  }

  public static String imageMime(byte[] data) {
    if (data.length >= 8
        && Arrays.equals(
            Arrays.copyOf(data, 8), new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
      return "image/png";
    if (data.length >= 4
        && (data[0] & 255) == 255
        && (data[1] & 255) == 216
        && (data[data.length - 2] & 255) == 255
        && (data[data.length - 1] & 255) == 217) return "image/jpeg";
    if (data.length >= 12
        && new String(data, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
        && new String(data, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WEBP"))
      return "image/webp";
    throw new IllegalArgumentException("Поддерживаются только PNG, JPEG и WebP");
  }
}
