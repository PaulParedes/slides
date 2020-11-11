package noria.scene;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

class FontMatch {
  final Integer weight;
  final int minSize;
  final int maxSize;
  volatile long id = -1;
  final String path;

  FontMatch(Integer weight, int minSize, int maxSize, String path) {
    this.weight = weight;
    this.minSize = minSize;
    this.maxSize = maxSize;
    this.path = path;
  }

  boolean matches(FontSpec spec) {
    return (weight == null || weight.equals(spec.weight))
           && minSize <= spec.size
           && spec.size < maxSize;
  }
}

public class FontManager {
  private static final FontManager INSTANCE = new FontManager();

  static {
    define("noria/resources/Inter-roman.otf", "UI", "Regular", null);
    define("noria/resources/Inter-italic.otf", "UI", "Italic", null);
  }

  private long nextId = 0;
  private final ConcurrentHashMap<String, Long> loadedIds = new ConcurrentHashMap<>(); // path -> fontId
  private final ConcurrentHashMap<String, CopyOnWriteArrayList<FontMatch>> matches = new ConcurrentHashMap<>();
  // matchKey -> List<FontMatch>

  private String matchKey(String family, String style) {
    return family + "/" + style;
  }

  public static void define(String path, String family, String style, Integer weight) {
    INSTANCE.defineImpl(path, family, style, weight, 0, Integer.MAX_VALUE);
  }

  public static void define(String path, String family, String style, Integer weight, int minSize) {
    INSTANCE.defineImpl(path, family, style, weight, minSize, Integer.MAX_VALUE);
  }

  public static void define(String path, String family, String style, Integer weight, int minSize, int maxSize) {
    INSTANCE.defineImpl(path, family, style, weight, minSize, maxSize);
  }

  private synchronized void defineImpl(String path, String family, String style, Integer weight, int minSize, int maxSize) {
    String matchKey = matchKey(family, style);
    CopyOnWriteArrayList<FontMatch> matchesList = matches.get(matchKey);
    if (matchesList == null) {
      matchesList = new CopyOnWriteArrayList<>();
      matches.put(matchKey, matchesList);
    }
    matchesList.add(new FontMatch(weight, minSize, maxSize, path));
  }

  private void maybeLoadImpl(FontMatch match) {
    if (match.id == -1L) {
      match.id = loadedIds.getOrDefault(match.path, -1L);
      if (match.id == -1L) {
        synchronized (loadedIds) {
          match.id = loadedIds.getOrDefault(match.path, -1L);
          if (match.id == -1L) {
            long id = nextId++;
            try {
              System.out.println("Loading " + match.path);

              byte[] bytes;
              InputStream ris = this.getClass().getClassLoader().getResourceAsStream(match.path);
              if (ris != null) {
                bytes = ris.readAllBytes();
              }
              else {
                bytes = Files.readAllBytes(FileSystems.getDefault().getPath(match.path));
              }
              PhotonApi.loadFont(id, bytes);
              loadedIds.put(match.path, id);
              match.id = id;
            }
            catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }
      }
    }
  }

  public static FontInstance resolve(FontSpec spec) {
    return INSTANCE.resolveImpl(spec);
  }

  private FontInstance resolveImpl(FontSpec spec) {
    String matchKey = matchKey(spec.family, spec.style);
    CopyOnWriteArrayList<FontMatch> matchesList = matches.get(matchKey);
    if (matchesList == null) {
      throw new RuntimeException("No FontInstance found for spec " + matchKey);
    }
    Optional<FontMatch> match = matchesList.stream().filter(m -> m.matches(spec)).findFirst();
    if (match.isEmpty()) {
      throw new RuntimeException("No FontInstance found for spec " + matchKey);
    }
    FontMatch fontMatch = match.get();
    maybeLoadImpl(fontMatch);
    if (fontMatch.weight == null) {
      return new FontInstance(fontMatch.id, spec.size, new FontVariation[]{new FontVariation(FontVariation.TAG_WEIGHT, spec.weight)});
    }
    else {
      return new FontInstance(fontMatch.id, spec.size);
    }
  }

  private LoadingCache<FontInstance, PhotonApi.FontMetrics> metricsCache =
    Caffeine.newBuilder()
    .maximumSize(1000)
    .executor(Runnable::run)
    .build(instance -> PhotonApi.fontMetrics(instance));

  private PhotonApi.FontMetrics fontMetricsImpl(FontInstance font) {
    return metricsCache.get(font);
  }

  public static PhotonApi.FontMetrics fontMetrics(FontInstance font) {
    return INSTANCE.fontMetricsImpl(font);
  }
}
