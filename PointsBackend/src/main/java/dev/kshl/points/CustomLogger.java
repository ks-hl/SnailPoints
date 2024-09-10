package dev.kshl.points;

import dev.kshl.kshlib.misc.StackUtil;
import dev.kshl.kshlib.misc.TimeUtil;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.TimeZone;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.*;

public class CustomLogger {

    public static Logger getLogger(String name, UnaryOperator<String> censor, @Nullable File file) {

        Logger logger = Logger.getLogger(name);
        logger.setUseParentHandlers(false);
        ConsoleHandler handler = new ConsoleHandler();
        handler.setFormatter(new CustomFormatter(censor, true, true));
        logger.addHandler(handler);

        if (file != null) {
            try {
                boolean ignored = file.getParentFile().mkdirs();
                FileHandler fh = new FileHandler(file.getAbsolutePath());
                fh.setFormatter(new CustomFormatter(censor, false, true));
                logger.addHandler(fh);
            } catch (IOException e) {
                print(logger, "Failed to initialize File logger", e);
                System.exit(0);
            }
        }

        return logger;
    }

    public static void print(Logger logger, String message, Throwable t) {
        if (message == null) message = "";
        else message += ": ";
        message += t.getMessage();
        logger.log(Level.WARNING, message, t);
    }

    public static class CustomFormatter extends SimpleFormatter {
        public static final String ANSI_RESET = "\u001B[0m";
        public static final String ANSI_RED = "\u001B[31m";
        public static final String ANSI_YELLOW = "\u001B[33m";
        public static final String ANSI_CYAN = "\u001B[96m";
        private final String format;
        private final UnaryOperator<String> censor;
        private final boolean useColor;

        public CustomFormatter(UnaryOperator<String> censor, boolean useColor, boolean time) {
            this.censor = censor;
            this.useColor = useColor;
            this.format = (time ? "[%1$tF %1$tT.%2$s] " : "") + "[%3$s] %4$s %n";
        }

        @Override
        public synchronized String format(LogRecord record) {
            String color = useColor ? switch (record.getLevel().toString()) {
                case "INFO" -> ANSI_CYAN;
                case "WARNING" -> ANSI_YELLOW;
                case "SEVERE" -> ANSI_RED;
                default -> ANSI_RESET;
            } : "";
            String millis = String.valueOf(record.getMillis() % 1000);
            millis = "0".repeat(3 - millis.length()) + millis;
            // Adjusted to fit the corrected format string with four placeholders
            String line = color + String.format(format, new Date(record.getMillis()), millis, record.getLevel().getLocalizedName(), record.getMessage());
            if (record.getThrown() != null) {
                line += " " + StackUtil.format(record.getThrown(), 0);
            }
            return censor.apply(line) + (useColor ? ANSI_RESET : "");
        }
    }

    public static class ConsumerHandler extends Handler {
        private final CustomFormatter formatter;
        private final Consumer<Record> consumer;

        public ConsumerHandler(UnaryOperator<String> censor, Consumer<Record> consumer) {
            this.formatter = new CustomFormatter(censor, false, false);
            this.consumer = consumer;
        }

        @Override
        public void publish(LogRecord record) {
            String line = formatter.format(record);
            consumer.accept(new Record(record.getMillis(), TimeUtil.format(record.getMillis(), DateTimeFormatter.ISO_LOCAL_DATE_TIME, TimeZone.getDefault().toZoneId()), line, record.getLevel()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() throws SecurityException {
        }

        public record Record(long timeMillis, String isoTime, String message, Level level) {
        }
    }
}
