package app.release.publisher.android;

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.services.androidpublisher.AndroidPublisher;
import com.google.api.services.androidpublisher.AndroidPublisherScopes;
import com.google.api.services.androidpublisher.model.AppEdit;
import com.google.api.services.androidpublisher.model.Bundle;
import com.google.api.services.androidpublisher.model.BundlesListResponse;
import com.google.api.services.androidpublisher.model.LocalizedText;
import com.google.api.services.androidpublisher.model.Track;
import com.google.api.services.androidpublisher.model.TrackRelease;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.release.model.CommandLineArguments;
import app.release.publisher.Publisher;

/**
 * Uploads android aab files to Play Store.
 */
public class AabPublisher implements Publisher {

    private static final String MIME_TYPE_AAB = "application/octet-stream";
    private final CommandLineArguments arguments;


    public AabPublisher(CommandLineArguments arguments) {
        this.arguments = arguments;
    }

    /**
     * Perform aab publish an release on given track
     *
     * @throws Exception Upload error
     */
    @Override
    public void publish() throws IOException {

        // load key file credentials
        System.out.println("Loading account credentials...");
        Path jsonKey = FileSystems.getDefault().getPath(arguments.getJsonKeyPath()).normalize();
        GoogleCredential cred = GoogleCredential.fromStream(new FileInputStream(jsonKey.toFile()));
        cred = cred.createScoped(Collections.singleton(AndroidPublisherScopes.ANDROIDPUBLISHER));

        // load aab file info
        System.out.println("Loading file information...");

        Path file = null;
        Integer versionCode = 0;
        if (arguments.getFile() != null) {
            file = FileSystems.getDefault().getPath(arguments.getFile()).normalize();
        } else {
            try {
                versionCode = Integer.valueOf(arguments.getVersionCode());
            } catch (NumberFormatException ex) {
                throw new NullPointerException("Neither File or Version code not defined!");
            }
        }

        String applicationName = arguments.getAppName();
        String packageName = arguments.getPackageName();
        String versionName = arguments.getVersionName();
        String tracks = arguments.getTrackName();

        System.out.println("Application Name: " + applicationName);
        System.out.println("Package Name: " + packageName);
        System.out.println("Version Name: " + versionName);
        System.out.println("Tracks: " + tracks);

        // load release notes
        System.out.println("Loading release notes...");
        List<LocalizedText> releaseNotes = new ArrayList<>();
        String language = new Locale("pt", "BR").toString();
        if (arguments.getNotesPath() != null) {
            Path notesFile = FileSystems.getDefault().getPath(arguments.getNotesPath()).normalize();
            String notesContent = null;
            try {
                notesContent = new String(Files.readAllBytes(notesFile));
                releaseNotes.add(new LocalizedText().setLanguage(language).setText(notesContent));
            } catch (IOException e) {
                e.printStackTrace();
            }
        } else if (arguments.getNotes() != null) {
            releaseNotes.add(new LocalizedText().setLanguage(language).setText(arguments.getNotes()));
        }

        // init publisher
        System.out.println("Initialising publisher service...");
        AndroidPublisher.Builder ab = new AndroidPublisher.Builder(cred.getTransport(), cred.getJsonFactory(), setHttpTimeout(cred));
        AndroidPublisher publisher = ab.setApplicationName(applicationName).build();

        // create an edit
        System.out.println("Initialising new edit...");
        AppEdit edit = null;
        try {
            edit = publisher.edits().insert(packageName, null).execute();
        } catch (IOException e) {
            e.printStackTrace();
        }
        final String editId = edit.getId();
        System.out.println(String.format("Edit created. Id: %s", editId));

        try {
            // publish the file
            Bundle bundle;
            if (file != null) {
                System.out.println("Uploading AAB file...");
                AbstractInputStreamContent aabContent = new FileContent(MIME_TYPE_AAB, file.toFile());
                bundle = publisher.edits().bundles().upload(packageName, editId, aabContent).execute();
                System.out.println(String.format("File uploaded. Version Code: %s", bundle.getVersionCode()));
            } else {
                System.out.println("No file to upload, searching bundle...");
                BundlesListResponse response = publisher.edits().bundles().list(packageName, editId).execute();

                final Integer version = versionCode;
                bundle = response.getBundles().stream()
                    .filter(bun -> version.equals(bun.getVersionCode()))
                    .findAny()
                    .orElse(null);

                if (bundle == null) throw new NullPointerException(String.format("Version code %s not found!", versionCode));
            }

            for (String trackName : tracks.split(",")) {
                String track = trackName;
                String fraction = "1";

                if (trackName.contains(":")) {
                    String[] names = trackName.split(":");
                    track = names[0];
                    fraction = names[1];
                }

                // create a release on track
                System.out.println(String.format("On tracks: %s. Creating a release...", trackName));

                Track trackEdit = new Track()
                    .setReleases(Collections.singletonList(buildRelease(versionName, fraction, bundle, releaseNotes)))
                    .setTrack(track);

                publisher.edits().tracks().update(packageName, editId, track, trackEdit).execute();
                System.out.println(String.format("Release created on track: %s", track));
            }

            // commit edit
            System.out.println("Committing edit...");
            publisher.edits().commit(packageName, editId).execute();
            System.out.println(String.format("Success. Committed Edit id: %s", editId));
        } catch (Exception e) {
            // error message
            String msg = "Operation Failed: " + e.getMessage();
            e.printStackTrace();
            // abort
            System.err.println("Operation failed due to an error!, Deleting edit...");
            try {
                publisher.edits().delete(packageName, editId).execute();
            } catch (Exception e2) {
                // log abort error as well
                msg += "\nFailed to delete edit: " + e2.getMessage();
            }

            // forward error with message
            throw new IOException(msg, e);
        }
    }

    private TrackRelease buildRelease(String versionName, String fraction, Bundle bundle, List<LocalizedText> releaseNotes) {
        Double userFraction = fractionToDouble(fraction);

        System.out.println(String.format("Version: %s - %f. Tracking...\n%s", versionName, userFraction, releaseNotes));

        return new TrackRelease()
            .setName(versionName)
            .setStatus(userFraction == 1D ? "completed" : "inProgress")
            .setUserFraction(userFraction)
            .setVersionCodes(Collections.singletonList((long) bundle.getVersionCode()))
            .setReleaseNotes(releaseNotes);
    }

    private Double fractionToDouble(String fraction) {
        try {
            double value = Double.parseDouble(fraction);
            return value > 0D && value <= 1D ? value : 1D;
        } catch (NullPointerException|NumberFormatException ex) {
            return 1D;
        }
    }

    private HttpRequestInitializer setHttpTimeout(final HttpRequestInitializer requestInitializer) {
        return httpRequest -> {
            requestInitializer.initialize(httpRequest);
            httpRequest.setConnectTimeout(3 * 60000);  // 3 minutes connect timeout
            httpRequest.setReadTimeout(3 * 60000);  // 3 minutes read timeout
        };
    }
}