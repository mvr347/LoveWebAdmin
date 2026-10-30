package me.lovelace.loveWebAdmin.utils;

/** Durations as people read them: "30 дн. 2 ч.", not "2599200 сек.". */
public final class Durations {
  private Durations() {}

  /** The two largest non-zero units, so a long ban does not read as a row of numbers. */
  public static String human(long seconds) {
    if (seconds <= 0) return "0 мин.";
    long[] sizes = {86400, 3600, 60, 1};
    String[] names = {"дн.", "ч.", "мин.", "сек."};
    var out = new StringBuilder();
    int shown = 0;
    long left = seconds;
    for (int i = 0; i < sizes.length && shown < 2; i++) {
      long value = left / sizes[i];
      left %= sizes[i];
      if (value == 0) {
        if (shown > 0) break;
        continue;
      }
      if (shown > 0) out.append(' ');
      out.append(value).append(' ').append(names[i]);
      shown++;
    }
    return out.toString();
  }
}
