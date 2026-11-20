package fr.minobot.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * Applies the log level of {@link Config} to Logback at runtime — the equivalent of {@code logger.py}.
 *
 * <p>The console appender comes from {@code logback.xml}; the rotating file appender is added here.
 * Minobot runs from the system tray, with no console to watch: the file is the only trace a player can
 * send back, so it is always written, capped, and rotated.
 */
public final class LoggerSetup {

    private static final String FILE_APPENDER_NAME = "MINOBOT_FILE";
    private static final String LOG_FILE = "logs/minobot.log";

    private static final String MAX_FILE_SIZE = "5MB";
    private static final int MAX_BACKUPS = 3;

    private LoggerSetup() {
    }

    public static void configure(Config config, Path dataDirectory) {
        final var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        final var root = context.getLogger(Logger.ROOT_LOGGER_NAME);

        root.setLevel(Level.toLevel(config.logLevel(), Level.INFO));

        final var logFile = dataDirectory.resolve(LOG_FILE);
        root.detachAppender(FILE_APPENDER_NAME);
        root.addAppender(fileAppender(context, logFile));
        root.info("Logging to file: {}", logFile);
    }

    private static RollingFileAppender<ILoggingEvent> fileAppender(
            LoggerContext context, Path logFile) {

        final var appender = new RollingFileAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.setName(FILE_APPENDER_NAME);
        appender.setFile(logFile.toString());

        final var rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setContext(context);
        rollingPolicy.setParent(appender);
        rollingPolicy.setFileNamePattern(logFile + ".%i");
        rollingPolicy.setMinIndex(1);
        rollingPolicy.setMaxIndex(MAX_BACKUPS);
        rollingPolicy.start();
        appender.setRollingPolicy(rollingPolicy);

        final var triggeringPolicy = new SizeBasedTriggeringPolicy<ILoggingEvent>();
        triggeringPolicy.setContext(context);
        triggeringPolicy.setMaxFileSize(FileSize.valueOf(MAX_FILE_SIZE));
        triggeringPolicy.start();
        appender.setTriggeringPolicy(triggeringPolicy);

        final var encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern("%d{yyyy-MM-dd HH:mm:ss} | %-5level | %msg%n");
        encoder.setCharset(java.nio.charset.StandardCharsets.UTF_8);
        encoder.start();
        appender.setEncoder(encoder);

        appender.start();
        return appender;
    }
}
