package io.github.profetgit.buildbuddy.ponder;

/** A lesson file that cannot be used: says where in the file and what is wrong ("groups[1].layers[0][2]: unknown block key 'x'"). */
public final class SceneException extends RuntimeException {
    public final String path;

    public SceneException(String path, String message) {
        super(path.isEmpty() ? message : path + ": " + message);
        this.path = path;
    }
}
