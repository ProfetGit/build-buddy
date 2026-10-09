package io.github.profetgit.buildbuddy.community;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** The questions the Community tab asks the site, each a blocking call (run them off the render thread). */
public final class CommunityClient {
    private final Net net;

    public CommunityClient(Net net) {
        this.net = net;
    }

    public Net net() {
        return net;
    }

    public Api.Categories categories() throws ApiException {
        return Api.categories(net.getText("/api/v1/categories"));
    }

    public Api.Listing builds(Api.Query q) throws ApiException {
        return Api.listing(net.getText("/api/v1/builds?" + q.queryString()));
    }

    public Api.Detail build(String id) throws ApiException {
        if (!Api.validId(id)) throw new ApiException(ApiException.Kind.BAD_REQUEST, "bad build id");
        return Api.detail(net.getText("/api/v1/builds/" + id));
    }

    /** The server-drawn preview picture (PNG bytes). The address comes from the build only if it is on the site we asked; else it is built from the id. */
    public byte[] thumbnail(Api.Build b) throws ApiException {
        String path = net.localPath(b.thumbnailUrl());
        if (path == null || !path.startsWith("/media/") || path.contains("..")) path = "/media/" + b.id() + "/thumb.png";
        return net.getBytes(path, Net.IMAGE_MAX, "image/png");
    }

    public Downloads.Result download(Api.Detail d, Path dir, Downloads.Validator validate, Net.ProgressSink progress, BooleanSupplier cancelled) throws ApiException {
        return Downloads.save(net, d, dir, validate, progress, cancelled);
    }
}
