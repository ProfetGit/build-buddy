package io.github.profetgit.buildbuddy.demo;

import static io.github.profetgit.buildbuddy.demo.Director.act;
import static io.github.profetgit.buildbuddy.demo.Director.check;
import static io.github.profetgit.buildbuddy.demo.Director.shot;
import static io.github.profetgit.buildbuddy.demo.Director.until;
import static io.github.profetgit.buildbuddy.demo.Director.waitTicks;

import io.github.profetgit.buildbuddy.community.Api;
import io.github.profetgit.buildbuddy.community.ApiException;
import io.github.profetgit.buildbuddy.community.Community;
import io.github.profetgit.buildbuddy.community.CommunityConfig;
import io.github.profetgit.buildbuddy.community.CommunityModel;
import io.github.profetgit.buildbuddy.community.Net;
import io.github.profetgit.buildbuddy.placement.BlueprintLibrary;
import io.github.profetgit.buildbuddy.placement.Placement;
import io.github.profetgit.buildbuddy.placement.Placements;
import io.github.profetgit.buildbuddy.ui.CommunityPane;
import io.github.profetgit.buildbuddy.ui.LibraryScreen;
import io.github.profetgit.buildbuddy.ui.SettingsScreen;
import io.github.profetgit.buildbuddy.ui.Settings;
import io.github.profetgit.buildbuddy.ui.Ui;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

/**
 * The Community tab in the real client against a local copy of the community site (`bun run dev` in
 * ~/Projects/cyanotype-community, http://localhost:3150, seeded). The scene expects the sample data of that site: it reads
 * the list straight from the API first and compares what the screen shows with it, then drives the tab through the real
 * click paths: the first-run notice, the grid, search, a category chip, sort, paging, a build's detail, a download, the
 * file arriving in the Library, a second download that must not replace the first, a .nbt build that cannot be downloaded,
 * offline, an empty search, the tab switched off, and no address.
 */
final class CommunityScenes {
    private CommunityScenes() {
    }

    private static final boolean NBT = !"false".equals(System.getProperty("buildbuddy.demo.nbt"));
    private static final String SITE = System.getProperty("buildbuddy.demo.site", CommunityConfig.LOCAL_TEST_URL);

    /** What the site says, read directly (not through the model under test). */
    private static final List<Api.Build> ALL = new ArrayList<>();
    private static volatile String reference = "waiting";
    private static int sentBeforeConsent = -1, sentAfterOff;
    private static String firstHash = "";

    private static Screen screen() {
        return Minecraft.getInstance().gui.screen();
    }

    private static LibraryScreen lib() {
        return (LibraryScreen) screen();
    }

    private static CommunityPane pane() {
        return lib().community();
    }

    private static CommunityModel model() {
        return pane().model();
    }

    private static void click(int[] at) {
        if (at == null) {
            check("community/click target exists", false, "no control there");
            return;
        }
        Screen s = screen();
        var info = new MouseButtonInfo(0, 0);
        s.mouseClicked(new MouseButtonEvent(at[0], at[1], info), false);
        s.mouseReleased(new MouseButtonEvent(at[0], at[1], info));
    }

    private static void clickControl(String id) {
        act(() -> click(pane().anchor(id)));
    }

    private static boolean idle() {
        CommunityModel m = model();
        return m != null && !m.loading() && !m.searchPending() && (m.listing() != null || m.error() != null);
    }

    private static String sha(Path f) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static List<String> titles(Api.Listing l) {
        return l.items().stream().map(Api.Build::title).toList();
    }

    static void community() {
        Director.clean();
        // the site's own list, read directly, to compare the screen against
        act(() -> Community.pool().execute(() -> {
            try {
                Net net = new Net(SITE, "BuildBuddy/demo-reference");
                ALL.addAll(Api.listing(net.getText("/api/v1/builds?sort=new&page=1&perPage=50")).items());
                reference = "ok " + ALL.size();
            } catch (ApiException e) {
                reference = "failed: " + e.forPlayer();
            }
        }));
        until("community/the site answers (is `bun run dev` running in cyanotype-community?)", 200, () -> !reference.equals("waiting"));
        act(() -> check("community/reference list read straight from the API", reference.startsWith("ok") && ALL.size() >= 12, reference));

        act(() -> {
            try {
                Path dir = BlueprintLibrary.saveDir();
                Files.createDirectories(dir);
                try (var files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) Files.deleteIfExists(f);
                }
            } catch (IOException e) {
                check("community/library emptied", false, e.toString());
            }
            Settings.get().community = CommunityConfig.ASK;
            Settings.get().communityUrl = SITE;
            Settings.changed();
            Director.hideHud(Minecraft.getInstance(), false);
            Minecraft.getInstance().gui.setScreen(new LibraryScreen());
        });
        waitTicks(6);
        act(() -> lib().showTab("COMMUNITY"));
        waitTicks(14);

        // ---- the first-run notice
        act(() -> {
            check("community/the User-Agent names the mod and its version", Community.userAgent().matches("BuildBuddy/\\d+\\.\\d+\\.\\d+"), Community.userAgent());
            sentBeforeConsent = Community.requestsSent();
            check("community/first run asks before it talks", pane().gate() == CommunityPane.Gate.NEEDS_CONSENT && pane().model() == null, "gate " + pane().gate());
            check("community/nothing was sent before the answer", sentBeforeConsent == 0, sentBeforeConsent + " requests");
            check("community/the notice has both answers", pane().controls().contains("consent:on") && pane().controls().contains("consent:off"), String.valueOf(pane().controls()));
        });
        shot("community_0_notice");
        waitTicks(60);
        act(() -> check("community/still nothing sent while the notice is up", Community.requestsSent() == 0, Community.requestsSent() + " requests"));
        clickControl("consent:on");
        until("community/the first page arrives", 400, CommunityScenes::idle);
        act(() -> {
            CommunityModel m = model();
            check("community/the answer was Turn on and it was remembered", Community.consent().equals(CommunityConfig.ON), Community.consent());
            check("community/no error", m.error() == null, String.valueOf(m.error()));
            Api.Listing l = m.listing();
            List<String> expect = ALL.stream().limit(CommunityModel.PER_PAGE).map(Api.Build::title).toList();
            check("community/the grid is the site's newest page, in order", l != null && titles(l).equals(expect), l == null ? "none" : titles(l).size() + " cards, first " + titles(l).get(0));
            check("community/the total is the site's", l != null && l.total() == ALL.size(), l == null ? "" : l.total() + " vs " + ALL.size());
            check("community/requests were sent now", Community.requestsSent() > 0, Community.requestsSent() + " requests");
        });
        until("community/the categories arrive", 200, () -> model().categories() != null);
        act(() -> check("community/the chips came with their counts", model().categories().categories().size() >= 5 && model().categories().categories().stream().mapToLong(Api.Category::builds).sum() == ALL.size(),
            model().categories().categories().size() + " categories, " + model().categories().categories().stream().mapToLong(Api.Category::builds).sum() + " builds"));
        until("community/previews load and get a texture", 600, () -> pane().textureCount() >= Math.min(4, pane().visibleCards()) && pane().textureCount() > 0);
        waitTicks(10);
        act(() -> check("community/cards are on screen", pane().visibleCards() >= 4, pane().visibleCards() + " visible, " + pane().textureCount() + " textures"));
        shot("community_1_grid");
        act(() -> {
            Api.Listing l = model().listing();
            Api.Build b = l.items().get(0);
            int[] c = pane().cardCenter(b.id());
            Ui.testMouse = c;
        });
        waitTicks(8);
        shot("community_1b_hover");
        act(() -> {
            Ui.testMouse = null;
            pane().mouseScrolled(-6);
        });
        waitTicks(25);
        shot("community_2_scrolled");
        act(() -> pane().mouseScrolled(10));
        waitTicks(20);

        // ---- search
        act(() -> lib().searchFor("castle"));
        until("community/search waits for a pause and then asks", 300, () -> idle() && model().query().q().equals("castle"));
        act(() -> {
            Api.Listing l = model().listing();
            long expect = ALL.stream().filter(b -> (b.title() + " " + b.author() + " " + b.summary()).toLowerCase().contains("castle")).count();
            check("community/a search shows matches only", l != null && !l.items().isEmpty() && l.items().stream().allMatch(b -> (b.title() + " " + b.author() + " " + b.summary() + " " + b.categoryLabel()).toLowerCase().contains("castle")),
                l == null ? "none" : titles(l).toString());
            check("community/search found at least what the title match says", l != null && l.total() >= Math.min(1, expect), l == null ? "" : l.total() + " vs " + expect);
        });
        waitTicks(6);
        shot("community_3_search");
        act(() -> lib().searchFor(""));
        until("community/clearing the search shows everything again", 300, () -> idle() && model().query().q().isEmpty() && model().listing() != null && model().listing().total() == ALL.size());

        // ---- a category chip
        clickControl("cat:ships");
        until("community/the Ships chip filters", 300, () -> idle() && model().query().category().equals("ships"));
        act(() -> {
            Api.Listing l = model().listing();
            long expect = ALL.stream().filter(b -> b.category().equals("ships")).count();
            check("community/a chip shows that category only", l != null && l.items().size() == expect && l.items().stream().allMatch(b -> b.category().equals("ships")), l == null ? "none" : l.items().size() + " vs " + expect);
        });
        waitTicks(6);
        shot("community_4_chip");
        clickControl("cat:");
        until("community/All shows everything again", 300, () -> idle() && model().query().category().isEmpty());

        // ---- sort
        clickControl("sort:popular");
        until("community/Popular re-sorts", 300, () -> idle() && model().query().sort().equals(Api.Query.POPULAR));
        act(() -> {
            Api.Listing l = model().listing();
            boolean ordered = true;
            for (int i = 1; i < l.items().size(); i++) if (l.items().get(i - 1).downloads() < l.items().get(i).downloads()) ordered = false;
            check("community/Popular is most downloaded first", ordered && l.items().get(0).downloads() >= 1, "first " + l.items().get(0).title() + " " + l.items().get(0).downloads());
        });
        waitTicks(6);
        shot("community_5_popular");
        clickControl("sort:new");
        until("community/Newest again", 300, () -> idle() && model().query().sort().equals(Api.Query.NEW));

        // ---- paging
        act(() -> check("community/the first page has Next and no Prev", pane().controls().contains("next") && !pane().controls().contains("prev"), String.valueOf(pane().controls())));
        clickControl("next");
        until("community/Next shows page 2", 300, () -> idle() && model().query().page() == 2);
        act(() -> {
            Api.Listing l = model().listing();
            List<String> expect = ALL.stream().skip(CommunityModel.PER_PAGE).limit(CommunityModel.PER_PAGE).map(Api.Build::title).toList();
            check("community/page 2 is what comes after page 1", l != null && titles(l).equals(expect) && l.page() == 2, l == null ? "none" : titles(l).size() + " cards");
            check("community/page 2 has Prev", pane().controls().contains("prev"), String.valueOf(pane().controls()));
        });
        waitTicks(10);
        shot("community_6_page2");
        clickControl("prev");
        until("community/Prev goes back", 300, () -> idle() && model().query().page() == 1);

        // ---- detail and download
        act(() -> lib().searchFor("Cozy Cottage"));
        until("community/find the cottage", 300, () -> idle() && model().query().q().equals("Cozy Cottage") && model().listing() != null && !model().listing().items().isEmpty());
        waitTicks(10);
        act(() -> click(pane().cardCenter(cottageId())));
        until("community/the detail view loads its data", 300, () -> model().detail() != null && model().detail().data != null);
        until("community/the big preview arrives", 300, () -> model().detail().big.pixels != null);
        waitTicks(10);
        act(() -> {
            CommunityModel.DetailState d = model().detail();
            check("community/detail shows description and materials", d.data != null && !d.data.materials().isEmpty() && d.data.sha256().length() == 64, d.data == null ? "no data" : d.data.materials().size() + " materials");
            check("community/Download is offered", pane().controls().contains("download") && CommunityModel.canDownload(d), String.valueOf(pane().controls()));
        });
        shot("community_7_detail");
        act(() -> lib().community().mouseScrolled(-4));
        waitTicks(20);
        shot("community_7b_detail_scrolled");
        act(() -> lib().community().mouseScrolled(10));
        waitTicks(10);
        clickControl("download");
        until("community/the download finishes", 400, () -> model().detail().download == CommunityModel.DownloadPhase.DONE || model().detail().download == CommunityModel.DownloadPhase.FAILED);
        act(() -> {
            CommunityModel.DetailState d = model().detail();
            check("community/the download succeeded", d.download == CommunityModel.DownloadPhase.DONE, String.valueOf(d.downloadError));
            Path saved = d.saved;
            try {
                check("community/it is in config/buildbuddy/blueprints under the build's name", saved != null && saved.getParent().equals(BlueprintLibrary.saveDir()) && saved.getFileName().toString().equals("Cozy Cottage.litematic"), String.valueOf(saved));
                check("community/the file is the one the site announced (sha-256)", saved != null && sha(saved).equals(d.data.sha256()), saved == null ? "" : sha(saved));
                check("community/its size is the announced size", saved != null && Files.size(saved) == d.summary.fileBytes(), saved == null ? "" : Files.size(saved) + " vs " + d.summary.fileBytes());
                check("community/the tags file says where it came from", saved != null && Files.readString(BlueprintLibrary.saveDir().resolve("Cozy Cottage.buildbuddy.json")).contains(d.summary.id()), "sidecar");
                firstHash = sha(saved);
            } catch (IOException e) {
                check("community/download checks", false, e.toString());
            }
        });
        waitTicks(6);
        shot("community_8_downloaded");

        // ---- it is in the normal Library
        clickControl("mine");
        waitTicks(10);
        until("community/the downloaded file is read by the Library", 300, () -> lib().tabName().equals("MINE") && lib().entries().stream().anyMatch(e -> e.fileName.equals("Cozy Cottage.litematic") && e.loaded()));
        act(() -> {
            var e = lib().entries().stream().filter(x -> x.fileName.equals("Cozy Cottage.litematic")).findFirst().orElseThrow();
            check("community/it appears in Mine with its blocks and community tag", e.info != null && e.info.blocks() > 0 && e.tags.contains("community"), e.info == null ? "no info" : e.info.blocks() + " blocks, tags " + e.tags);
            lib().searchFor("community");
        });
        waitTicks(8);
        act(() -> check("community/the tag 'community' finds it in Mine", lib().entries().size() == 1, lib().entries().size() + " shown"));
        shot("community_9_mine");
        act(() -> lib().searchFor(""));

        // ---- the same build again must not replace the file
        reopenCottage("COMMUNITY");
        waitTicks(10);
        act(() -> click(pane().cardCenter(cottageId())));
        until("community/opening it again finds the copy you already have", 300, () -> model().detail() != null && model().detail().data != null && model().detail().existing != null);
        waitTicks(8);
        act(() -> check("community/the detail says you already have it", pane().controls().contains("place") && pane().controls().contains("download"), String.valueOf(pane().controls())));
        shot("community_10_have_it");
        clickControl("download");
        until("community/downloading again finishes", 400, () -> model().detail().download == CommunityModel.DownloadPhase.DONE || model().detail().download == CommunityModel.DownloadPhase.FAILED);
        act(() -> {
            CommunityModel.DetailState d = model().detail();
            try {
                Path first = BlueprintLibrary.saveDir().resolve("Cozy Cottage.litematic");
                check("community/the second download is Cozy Cottage 2", d.saved != null && d.saved.getFileName().toString().equals("Cozy Cottage 2.litematic"), String.valueOf(d.saved));
                check("community/the first file is untouched", sha(first).equals(firstHash), sha(first));
                check("community/both files are there", Files.exists(BlueprintLibrary.saveDir().resolve("Cozy Cottage 2.litematic")) && Files.exists(first), "two files");
            } catch (IOException e) {
                check("community/second download checks", false, e.toString());
            }
        });

        // ---- place it
        clickControl("place");
        waitTicks(14);
        until("community/Place it starts placing", 200, () -> screen() == null && Placements.mode() == Placements.Mode.PLACING);
        act(() -> {
            Placement p = Placements.active();
            check("community/the placing ghost is the downloaded build", p != null && p.name.startsWith("Cozy Cottage"), p == null ? "none" : p.name);
            for (Placement x : java.util.List.copyOf(Placements.all())) Placements.remove(x);
            Placements.setMode(Placements.Mode.IDLE);
            Minecraft.getInstance().gui.setScreen(new LibraryScreen());
        });
        waitTicks(6);
        act(() -> lib().showTab("COMMUNITY"));
        waitTicks(10);
        until("community/the list is back after reopening", 300, CommunityScenes::idle);

        // ---- a build in a format this version cannot open (needs an .nbt or .schem build on the site: NBT=0 skips it)
        if (NBT) nbtScene();

        // ---- an empty search
        act(() -> lib().searchFor("zzzzqq"));
        until("community/a search with no matches", 300, () -> idle() && model().query().q().equals("zzzzqq"));
        waitTicks(8);
        act(() -> check("community/empty says so and offers to clear", model().listing().items().isEmpty() && pane().controls().contains("clear"), String.valueOf(pane().controls())));
        shot("community_13_empty");
        clickControl("clear");
        until("community/Clear filters shows everything again", 300, () -> idle() && model().query().q().isEmpty() && model().listing() != null && model().listing().total() == ALL.size());
        waitTicks(4);
        act(() -> check("community/and empties the search box too", lib().searchValue().isEmpty() && model().query().category().isEmpty(), "box '" + lib().searchValue() + "'"));

        // ---- offline
        act(() -> {
            Settings.get().communityUrl = "http://localhost:1";
            Settings.changed();
        });
        until("community/an address nobody listens on is an error state", 400, () -> model() != null && model().error() != null);
        waitTicks(10);
        act(() -> {
            check("community/offline is named as offline", model().error().kind == ApiException.Kind.OFFLINE, String.valueOf(model().error().kind));
            check("community/offline offers Try again", pane().controls().contains("retry"), String.valueOf(pane().controls()));
        });
        shot("community_14_offline");
        act(() -> {
            Settings.get().communityUrl = SITE;
            Settings.changed();
        });
        until("community/back online it loads by itself", 400, () -> model() != null && model().error() == null && model().listing() != null);

        // ---- switched off
        act(() -> {
            Settings.get().community = CommunityConfig.OFF;
            Settings.changed();
        });
        waitTicks(12);
        act(() -> sentAfterOff = Community.requestsSent());
        waitTicks(60);
        act(() -> {
            check("community/off shows the off notice and drops the model", pane().gate() == CommunityPane.Gate.OFF && model() == null && pane().controls().contains("consent:on"), "gate " + pane().gate());
            check("community/nothing is sent while off (even what was queued)", Community.requestsSent() == sentAfterOff, Community.requestsSent() + " vs " + sentAfterOff);
        });
        shot("community_15_off");

        // ---- no address
        act(() -> {
            Settings.get().community = CommunityConfig.ON;
            Settings.get().communityUrl = "";
            Settings.changed();
        });
        waitTicks(30);
        act(() -> {
            check("community/with no address at all it says so", pane().gate() == CommunityPane.Gate.NO_ADDRESS && model() == null, "gate " + pane().gate());
            check("community/and sends nothing", Community.requestsSent() == sentAfterOff, Community.requestsSent() + " vs " + sentAfterOff);
        });
        shot("community_16_no_address");
        act(() -> {
            Settings.get().community = CommunityConfig.ASK;
            Settings.changed();
        });
        waitTicks(6);
        clickControl("localSite");
        waitTicks(12);
        act(() -> {
            check("community/Use local site sets the address and the first-run question follows, no trip to Settings", pane().gate() == CommunityPane.Gate.NEEDS_CONSENT && CommunityConfig.LOCAL_TEST_URL.equals(Settings.get().communityUrl), "gate " + pane().gate());
            check("community/and still nothing is sent", Community.requestsSent() == sentAfterOff, Community.requestsSent() + " vs " + sentAfterOff);
        });
        shot("community_16b_then_asks");

        // ---- settings
        act(() -> {
            Settings.get().communityUrl = SITE;
            Settings.get().community = CommunityConfig.ON;
            Settings.changed();
            Minecraft.getInstance().gui.setScreen(new SettingsScreen());
        });
        waitTicks(10);
        act(() -> click(((SettingsScreen) screen()).anchor("tab:COMMUNITY")));
        waitTicks(14);
        act(() -> check("community/Settings has a Community group", ((SettingsScreen) screen()).groupName().equals("COMMUNITY") && ((SettingsScreen) screen()).anchor("community") != null, ((SettingsScreen) screen()).groupName()));
        shot("community_17_settings");
        act(() -> click(((SettingsScreen) screen()).anchor("community")));
        waitTicks(6);
        act(() -> check("community/the switch in Settings turns it off", Community.consent().equals(CommunityConfig.OFF), Community.consent()));
        act(() -> click(((SettingsScreen) screen()).anchor("community")));
        waitTicks(6);
        act(() -> check("community/and on again", Community.consent().equals(CommunityConfig.ON), Community.consent()));

        // leave the settings as a new player has them
        act(() -> {
            Settings.get().community = CommunityConfig.ASK;
            Settings.get().communityUrl = "";
            Settings.changed();
            Settings.flush();
            Ui.testMouse = null;
            Minecraft.getInstance().gui.setScreen(null);
            Director.hideHud(Minecraft.getInstance(), true);
        });
        waitTicks(4);
    }

    private static void nbtScene() {
        act(() -> lib().searchFor("Spruce cabin"));
        until("community/find the .nbt build", 300, () -> idle() && model().query().q().equals("Spruce cabin") && model().listing() != null && !model().listing().items().isEmpty());
        waitTicks(12);
        shot("community_11_nbt_card");
        act(() -> click(pane().cardCenter(ALL.stream().filter(b -> b.fileExtension().equals("nbt")).findFirst().orElseThrow().id())));
        until("community/the .nbt detail loads", 300, () -> model().detail() != null && model().detail().data != null);
        waitTicks(10);
        act(() -> {
            CommunityModel.DetailState d = model().detail();
            check("community/an .nbt build cannot be downloaded here, and it says why", !CommunityModel.canDownload(d) && !d.summary.canOpen(), "canDownload " + CommunityModel.canDownload(d));
            check("community/it can still be opened on its page", pane().controls().contains("website"), String.valueOf(pane().controls()));
            long files = countFiles();
            model().download();
            check("community/asking to download it does nothing", d.download == CommunityModel.DownloadPhase.IDLE && countFiles() == files, d.download + ", " + files + " files");
        });
        shot("community_12_nbt_detail");
        act(() -> lib().keyPressed(new net.minecraft.client.input.KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0)));
        waitTicks(6);
        act(() -> check("community/Esc in the detail goes back to the list, not out of the Library", model().detail() == null && screen() instanceof LibraryScreen, "screen " + screen()));
        act(() -> lib().searchFor(""));
        until("community/list again", 300, () -> idle() && model().query().q().isEmpty());
    }

    private static long countFiles() {
        try (var files = Files.list(BlueprintLibrary.saveDir())) {
            return files.count();
        } catch (IOException e) {
            return -1;
        }
    }

    private static void reopenCottage(String tab) {
        act(() -> {
            lib().showTab(tab);
            lib().searchFor("Cozy Cottage");
        });
        until("community/the list for the cottage", 300, () -> model() != null && idle() && model().query().q().equals("Cozy Cottage") && model().listing() != null && !model().listing().items().isEmpty());
    }

    private static String cottageId() {
        return ALL.stream().filter(b -> b.title().equals("Cozy Cottage")).findFirst().map(Api.Build::id).orElse("");
    }
}
