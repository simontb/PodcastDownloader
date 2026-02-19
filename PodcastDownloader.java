//DEPS com.rometools:rome:2.1.0
//DEPS com.mpatric:mp3agic:0.9.1
//DEPS com.squareup.okhttp3:okhttp:4.12.0
//DEPS org.jsoup:jsoup:1.17.2
//DEPS org.threeten:threeten-extra:1.8.0

import com.mpatric.mp3agic.ID3v2;
import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSink;
import okio.Okio;

/// usr/bin/env jbang "$0" "$@" ; exit $?

private static final OkHttpClient client = new OkHttpClient();
private static final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("yyyyMMdd");

void main(String[] args) throws Exception {
    if (args.length < 2) {
        System.err.println("Usage: PodcastDownloader <rss_url_or_file> <output_dir>");
        System.err.println("RSS can be remote URL or local file path.");
        System.exit(1);
    }

    final String rssSource = args[0];
    final Path outDir = Paths.get(args[1]).toAbsolutePath().normalize();

    // Handle local file or URL
    final InputStream rssStream;
    if (Files.exists(Paths.get(rssSource))) {
        rssStream = Files.newInputStream(Paths.get(rssSource));
        IO.println("Reading local RSS file: " + rssSource);
    } else {
        IO.println("Downloading RSS from: " + rssSource);
        final Request request = new Request.Builder().url(rssSource).build();
        try (final Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Failed to fetch RSS: HTTP " + response.code());
            }
            rssStream = response.body().byteStream();
        }
    }

    final byte[] rssBytes;
    try {
        rssBytes = rssStream.readAllBytes();
    } finally {
        rssStream.close();
    }

    final Charset rssCharset = detectCharset(rssBytes);
    final String rssXml = new String(rssBytes, rssCharset);
    String fixedXml = ensureKnownNamespaces(rssXml);
    fixedXml = fixVoidTags(fixedXml);

    final SyndFeedInput input = new SyndFeedInput();
    final SyndFeed feed;
    try (final InputStream fixedStream = new ByteArrayInputStream(fixedXml.getBytes(rssCharset))) {
        feed = input.build(new XmlReader(fixedStream));
    }

    final String podcastTitle = feed.getTitle() != null ? sanitizeFilename(feed.getTitle()) : "Podcast";
    final Path baseDir = outDir.resolve(podcastTitle);
    Files.createDirectories(baseDir);

    IO.println("Podcast: " + feed.getTitle());
    IO.println("Saving to: " + baseDir);

    final Map<String, List<String>> errors = new HashMap<>();
    for (final SyndEntry entry : feed.getEntries()) {
        final String title = entry.getTitle() != null ? entry.getTitle() : "Episode";
        final String titleSanitized = sanitizeFilename(title);

        final Instant pubInstant = parsePubDate(entry);
        final ZonedDateTime pubDate = pubInstant.atZone(ZoneId.systemDefault());
        final String datePrefix = dateFormatter.format(pubDate);
        final String filename = datePrefix + " - " + titleSanitized + ".mp3";
        final Path destPath = baseDir.resolve(filename);

        if (Files.exists(destPath)) {
            IO.println("Skipping existing: " + filename);
            errors.computeIfAbsent("Already Existing", _ -> new ArrayList<>()).add(filename);
            continue;
        }

        final String audioUrl = getAudioUrl(entry);
        if (audioUrl == null) {
            IO.println("No audio URL for entry: " + title);
            errors.computeIfAbsent("No audio URL", _ -> new ArrayList<>()).add(filename);
            continue;
        }

        IO.println("Downloading: " + title);
        IO.println("  URL: " + audioUrl);

        final Path tempDestPath = baseDir.resolve("temp-" + filename);
        try {
            downloadFile(audioUrl, tempDestPath);
        } catch (Exception e) {
            System.err.println("  Error downloading audio: " + e.getMessage());
            if (Files.exists(tempDestPath))
                Files.delete(tempDestPath);
            errors.computeIfAbsent("Error downloading audio", _ -> new ArrayList<>()).add(filename);
            continue;
        }

        try {
            addTags(tempDestPath, destPath, title, podcastTitle, entry.getAuthor(), pubInstant);
        } catch (Exception e) {
            System.err.println("  Error adding tags: " + e.getMessage());
            errors.computeIfAbsent("Error adding tags", _ -> new ArrayList<>()).add(filename);
        }

        try {
            setFileTime(destPath, pubInstant);
        } catch (Exception e) {
            System.err.println("  Error setting file time: " + e.getMessage());
            errors.computeIfAbsent("Error setting file time", _ -> new ArrayList<>()).add(filename);
        }
    }
    IO.println("\nDone.");
    IO.println("\nIssues");
    errors.forEach((key, value) -> IO.println("\n\n" + key + ":\n" + String.join("\n", value)));
}

private static String sanitizeFilename(String name) {
    return name.trim()
            .replaceAll("[*?<>]", "")
            .replaceAll("\"", "'")
            .replaceAll("[\\\\/|]", "-")
            .replaceAll("[\\\\/:]", " -")
            .replaceAll("\\s+", " ")
            .strip();
}

private static Instant parsePubDate(final SyndEntry entry) {
    if (entry.getPublishedDate() != null) {
        return entry.getPublishedDate().toInstant();
    } else if (entry.getUpdatedDate() != null) {
        return entry.getUpdatedDate().toInstant();
    }
    return Instant.now();
}

private static String getAudioUrl(final SyndEntry entry) {
    if (entry.getEnclosures() != null) {
        return entry.getEnclosures().stream()
                .filter(e -> e.getType() != null && (e.getType().startsWith("audio/") || e.getUrl().toLowerCase().endsWith(".mp3")))
                .findFirst()
                .map(SyndEnclosure::getUrl)
                .orElse(null);
    }
    return null;
}

private static Charset detectCharset(final byte[] bytes) {
    final int probeLen = Math.min(bytes.length, 200);
    final String probe = new String(bytes, 0, probeLen, StandardCharsets.ISO_8859_1);
    final Matcher matcher = Pattern.compile("encoding=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE).matcher(probe);
    if (matcher.find()) {
        try {
            return Charset.forName(matcher.group(1));
        } catch (Exception ignored) {
        }
    }
    return StandardCharsets.UTF_8;
}

private static String ensureKnownNamespaces(final String xml) {
    String updated = xml;
    // Namespaces are not actually used, so use my own domain
    updated = ensureNamespace(updated, "atom", "https://simontb.de/Atom");
    updated = ensureNamespace(updated, "itunes", "https://simontb.de");
    updated = ensureNamespace(updated, "media", "https://simontb.de/");
    updated = ensureNamespace(updated, "content", "https://simontb.de");
    return updated;
}

private static String ensureNamespace(final String xml, final String prefix, final String uri) {
    if (!xml.contains(prefix + ":")) return xml;
    if (xml.contains("xmlns:" + prefix + "=")) return xml;

    Matcher matcher = Pattern.compile("<(rss|feed|rdf:RDF)(\\s[^>]*)?>", Pattern.CASE_INSENSITIVE).matcher(xml);
    if (!matcher.find()) return xml;

    final String fullTag = matcher.group(0);
    final String name = matcher.group(1);
    final  String attrs = matcher.group(2) == null ? "" : matcher.group(2);
    final  String suffix = fullTag.endsWith("/>") ? "/>" : ">";
    final  String replacement = "<" + name + attrs + " " + "xmlns:" + prefix + "=\"" + uri + "\"" + suffix;

    return xml.substring(0, matcher.start()) + replacement + xml.substring(matcher.end());
}

private static String fixVoidTags(final String xml) {
    final Pattern pattern = Pattern.compile("<(br|hr|img|input|source|track|wbr)([^>]*)>", Pattern.CASE_INSENSITIVE);
    final Matcher matcher = pattern.matcher(xml);
    final StringBuilder stringBuilder = new StringBuilder();
    while (matcher.find()) {
        final String tag = matcher.group(1);
        final String attrs = matcher.group(2) == null ? "" : matcher.group(2);
        final String trimmed = attrs.trim();
        if (trimmed.endsWith("/")) {
            matcher.appendReplacement(stringBuilder, "<" + tag + attrs + ">");
        } else {
            matcher.appendReplacement(stringBuilder, "<" + tag + attrs + "/>");
        }
    }
    matcher.appendTail(stringBuilder);
    return stringBuilder.toString();
}

private static void downloadFile(final String url, final Path dest) throws IOException {
    final Request request = new Request.Builder().url(url).build();
    try (final Response response = client.newCall(request).execute()) {
        if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
        try (InputStream in = response.body().byteStream();
             BufferedSink sink = Okio.buffer(Okio.sink(dest))) {
            in.transferTo(sink.outputStream());
        }
    }
}

private static void addTags(final Path tempMp3Path, final Path mp3Path, final String title, final String podcastTitle, final String author, final Instant pubDate) throws Exception {
    final File mp3File = tempMp3Path.toFile();
    final Mp3File mp3 = new Mp3File(mp3File);
    ID3v2 id3 = mp3.getId3v2Tag();
    if (id3 == null) {
        id3 = new ID3v24Tag();
        mp3.setId3v2Tag(id3);
    }

    id3.setTitle(title);
    id3.setAlbum(podcastTitle);
    if (author != null) id3.setArtist(author);
    id3.setDate(pubDate.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));

    IO.println("Writing file to " + mp3Path.toString());
    mp3.save(mp3Path.toString());
    Files.deleteIfExists(tempMp3Path);
}

private static void setFileTime(Path path, Instant instant) throws IOException {
    Files.setLastModifiedTime(path, FileTime.from(instant));
}
