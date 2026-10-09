package io.github.profetgit.buildbuddy.community;

import com.google.gson.JsonObject;
import io.github.profetgit.buildbuddy.placement.BlueprintSaver;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Getting a build from the site into the blueprint folder: the file is fetched to a temporary name, checked (size, the
 * checksum the site announced, that it is a gzip file the mod can read), and only then moved to a name nobody has. A file
 * that is already in the folder is never replaced: the new one becomes "name 2". After that it is an ordinary file in the
 * Library, with an entry in the library index that remembers where it came from.
 */
public final class Downloads {
    /** Opens the file the way the game will; throws if it cannot be read. Tests pass a stub, the game passes the real reader. */
    @FunctionalInterface
    public interface Validator {
        void check(Path file) throws Exception;
    }

    public record Result(Path file, long bytes) {
    }

    private Downloads() {
    }

    public static String downloadPath(Api.Build b) {
        return "/api/v1/builds/" + b.id() + "/download";
    }

    public static Result save(Net net, Api.Detail d, Path dir, Validator validate, Net.ProgressSink progress, BooleanSupplier cancelled) throws ApiException {
        Api.Build b = d.build();
        if (!b.canOpen()) {
            throw new ApiException(ApiException.Kind.UNREADABLE, "it is a ." + (b.fileExtension().isEmpty() ? "?" : b.fileExtension()) + " file and this version opens .litematic, .schem and .schematic");
        }
        // the file keeps the kind it is: the reader chooses by the name, and the Library lists it as what it is
        String ext = b.fileExtension().toLowerCase(java.util.Locale.ROOT);
        Path tmp = null;
        try {
            Files.createDirectories(dir);
            tmp = Files.createTempFile(dir, "download-", "." + ext);
            Net.Fetched got = net.downloadTo(downloadPath(b), tmp, Net.DOWNLOAD_MAX, progress, cancelled);
            if (b.fileBytes() >= 0 && got.bytes() != b.fileBytes()) {
                throw new ApiException(ApiException.Kind.CHECKSUM, "expected " + b.fileBytes() + " bytes, got " + got.bytes());
            }
            for (String announced : new String[]{d.sha256(), got.headerSha256()}) {
                if (!announced.isEmpty() && !announced.equals(got.sha256())) throw new ApiException(ApiException.Kind.CHECKSUM, "checksum differs");
            }
            if (!looksGzip(tmp)) throw new ApiException(ApiException.Kind.UNREADABLE, "it is not a schematic file");
            try {
                validate.check(tmp);
            } catch (Exception e) {
                throw new ApiException(ApiException.Kind.UNREADABLE, 0, 0, e.getMessage() == null ? e.toString() : e.getMessage(), e);
            }
            Path target = place(tmp, dir, BlueprintSaver.stem(b.title()), ext);
            writeSidecar(dir, target, b, net.base(), got.sha256());
            tmp = null;
            return new Result(target, got.bytes());
        } catch (IOException e) {
            throw new ApiException(ApiException.Kind.DISK, 0, 0, e.getMessage() == null ? e.toString() : e.getMessage(), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a stray download-*.tmp-like file is harmless; a .schem/.litematic named download-... would only show if the move failed half way
                }
            }
        }
    }

    /**
     * Puts the finished file under the first free name. The name is claimed first by creating the (empty) file with the
     * create-new flag, which only one of two downloads at once can win; the finished file then replaces the empty one in a
     * single rename. A plain move would check and then rename, and two renames can both pass the check.
     */
    static Path place(Path tmp, Path dir, String stem, String ext) throws IOException {
        for (int attempt = 0; attempt < 200; attempt++) {
            Path target = BlueprintSaver.freePath(dir, stem, ext, true);
            try {
                Files.createFile(target);
            } catch (FileAlreadyExistsException raced) {
                continue;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return target;
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
        }
        throw new IOException("no free name for " + stem);
    }

    private static boolean looksGzip(Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        }
    }

    private static void writeSidecar(Path dir, Path file, Api.Build b, String base, String sha) {
        List<String> tagList = new ArrayList<>();
        tagList.add("community");
        if (!b.category().isEmpty()) tagList.add(b.category());
        JsonObject src = new JsonObject();
        src.addProperty("site", base);
        src.addProperty("id", b.id());
        src.addProperty("title", b.title());
        src.addProperty("author", b.author());
        src.addProperty("sha256", sha);
        io.github.profetgit.buildbuddy.placement.LibraryIndex.put(file, tagList, src);
    }

    /** The file already in the folder that came from this build of this site, if any. */
    public static Path existingCopy(Path dir, String base, String id) {
        Path file = io.github.profetgit.buildbuddy.placement.LibraryIndex.findBySource(base, id);
        return file != null && dir.toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize().getParent()) ? file : null;
    }
}
