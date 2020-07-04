package app.release.publisher;

import app.release.publisher.android.AabPublisher;
import app.release.model.CommandLineArguments;

public class PublisherFactory {

    private PublisherFactory() { }

    public static Publisher buildPublisher(CommandLineArguments arguments) {
        return new AabPublisher(arguments);
    }
}
