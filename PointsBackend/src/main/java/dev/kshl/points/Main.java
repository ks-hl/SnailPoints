package dev.kshl.points;

import dev.kshl.kshlib.exceptions.BusyException;
import dev.kshl.kshlib.misc.FileUtil;
import dev.kshl.kshlib.misc.TimeUtil;
import dev.kshl.kshlib.sql.ConnectionManager;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class Main {
    private static final Logger logger;
    private static final Pattern API_KEY_PATTERN = Pattern.compile("[0-9a-zA-Z_]{30,}");
    public static final UnaryOperator<String> KEY_CENSOR = msg -> API_KEY_PATTERN.matcher(msg).replaceAll("**REDACTED**");
    private static final boolean isTestEnvironment = new File("IS_TEST_ENVIRONMENT").exists();
    private static boolean isDebug = isIsTestEnvironment();

    static {
        String dateTime = TimeUtil.FILE_FORMAT2.format(LocalDateTime.now());
        File file = FileUtil.getFirstNewFile(new File("logs"), dateTime, "log", "-%s");
        try {
            new File("logs/latest.log").delete();
            Files.createSymbolicLink(Path.of(new File("logs/latest.log").getAbsolutePath()), Path.of(file.getAbsolutePath()));
        } catch (FileSystemException e) {
            System.err.println("Failed to create 'latest' symlink, " + e.getMessage());
        } catch (IOException e) {
            System.err.println("Failed to create 'latest' symlink");
            e.printStackTrace();
        }
        logger = CustomLogger.getLogger("Points", KEY_CENSOR, file);
    }

    public static void main(String[] args) {
        SQLManager sqlManager_;
        try {
            sqlManager_ = new SQLManager(new File("data.db"));
            sqlManager_.init();

            try {
                int demo1 = sqlManager_.getUIDManager().getIDOpt("demo1", true).orElse(-1);
                sqlManager_.createAccount(demo1, "demo1@demo.com");
                sqlManager_.getValidatedAccountsManager().add(demo1);
                sqlManager_.getPasswordManager().setPassword(demo1, "TYR9pXA4lCFrMcqJ", 0);
            } catch (BusyException e) {
                throw new RuntimeException(e);
            } catch (SQLException e) {
                if (!ConnectionManager.isConstraintViolation(e)) throw e;
            }
        } catch (IOException | SQLException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        final SQLManager sqlManager = sqlManager_;

        JSONObject env = new JSONObject(FileUtil.read(new File(".env.json")));
        JSONObject envMail = env.getJSONObject("email");

        MailHelper mailHelper = new MailHelper(envMail.getString("host"), envMail.getInt("port"), envMail.getString("from"), envMail.getString("password"));

        EmailChallenger emailChallenger = new EmailChallenger(mailHelper);
        int numberOfProxies = env.optInt("number_of_proxies", 0);
        int port = 8069;
        PointsWebServer pointsWebServer = new PointsWebServer(port, sqlManager, emailChallenger, numberOfProxies);
        info("Starting web server on port " + port);
        new Thread(pointsWebServer).start();
    }

    public static Logger getLogger() {
        return logger;
    }

    public static void print(String message, Throwable t) {
        CustomLogger.print(logger, message, t);
    }

    public static void info(String message) {
        logger.info(message);
    }

    public static void warning(String message) {
        logger.warning(message);
    }

    public static void debug(String s) {
        debug(() -> s);
    }

    public static void debug(Supplier<String> supplier) {
        if (!isDebug()) return;
        info("[DEBUG] " + supplier.get());
    }

    public static boolean isDebug() {
        return isDebug;
    }

    public static void setDebug(boolean isDebug) {
        Main.isDebug = isDebug;
    }

    public static boolean isIsTestEnvironment() {
        return isTestEnvironment;
    }
}