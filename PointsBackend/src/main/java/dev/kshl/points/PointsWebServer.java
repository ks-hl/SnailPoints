package dev.kshl.points;

import dev.kshl.kshlib.concurrent.ConcurrentHashMap;
import dev.kshl.kshlib.exceptions.BusyException;
import dev.kshl.kshlib.function.ThrowingConsumer;
import dev.kshl.kshlib.json.JSONCollector;
import dev.kshl.kshlib.net.HTTPRequestType;
import dev.kshl.kshlib.net.HTTPResponseCode;
import dev.kshl.kshlib.net.WebServer;
import dev.kshl.kshlib.sql.ConnectionManager;
import dev.kshl.kshlib.sql.SQLSessionTokenManager;
import dev.kshl.kshlib.sql.SettingManager;
import org.json.JSONObject;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public class PointsWebServer extends WebServer {
    private static final long LOGIN_DELAY_TIME = 1000;

    public static final class PasswordRequirements {
        public static final int MIN_LENGTH = 12;
        public static final int MAX_LENGTH = 64;
        public static final int MIN_UPPER_CASE_CHARACTERS = 1;
        public static final int MIN_LOWER_CASE_CHARACTERS = 1;
        public static final int MIN_SPECIAL_CHARACTERS = 1;

        public static void validatePassword(String password) throws WebException {
            if (password == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No password provided");
            }

            if (password.length() < PasswordRequirements.MIN_LENGTH) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Password must be at least " + PasswordRequirements.MIN_LENGTH + " characters");
            }
            if (password.length() > PasswordRequirements.MAX_LENGTH) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Password must be no more than " + PasswordRequirements.MAX_LENGTH + " characters");
            }
            if (!password.matches("[a-zA-Z0-9_\\-\\s!@#$%^&*()+=`~'\";\\[\\]{},.<>/?\\\\|]+")) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Your password contains invalid characters");
            }
            int upper = 0, lower = 0, special = 0;
            for (char c : password.toCharArray()) {
                if (c >= 'A' && c <= 'Z') upper++;
                else if (c >= 'a' && c <= 'z') lower++;
                else special++;
            }
            if (upper < PasswordRequirements.MIN_UPPER_CASE_CHARACTERS) {
                //noinspection ConstantValue
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, String.format("Your password must have at least %s capital letter%s.", PasswordRequirements.MIN_UPPER_CASE_CHARACTERS, PasswordRequirements.MIN_UPPER_CASE_CHARACTERS == 1 ? "" : "s"));
            }
            if (lower < PasswordRequirements.MIN_LOWER_CASE_CHARACTERS) {
                //noinspection ConstantValue
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, String.format("Your password must have at least %s lower case letter%s.", PasswordRequirements.MIN_LOWER_CASE_CHARACTERS, PasswordRequirements.MIN_LOWER_CASE_CHARACTERS == 1 ? "" : "s"));
            }
            if (special < PasswordRequirements.MIN_SPECIAL_CHARACTERS) {
                //noinspection ConstantValue
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, String.format("Your password must have at least %s special letter%s.", PasswordRequirements.MIN_SPECIAL_CHARACTERS, PasswordRequirements.MIN_SPECIAL_CHARACTERS == 1 ? "" : "s"));
            }
        }
    }

    private static class LoginLocker {
        private final ReentrantLock lock = new ReentrantLock();
        private final long lastLocked = System.currentTimeMillis();

        public boolean tryLock(long waitMillis) throws InterruptedException {
            return lock.tryLock(waitMillis, TimeUnit.MILLISECONDS);
        }

        public void unlock() {
            lock.unlock();
        }
    }

    private final ConcurrentHashMap<String, LoginLocker> loginLock = new ConcurrentHashMap<>(new HashMap<>());
    private final ConcurrentHashMap<String, List<Long>> failedLoginAttemptUsernames = new ConcurrentHashMap<>(new HashMap<>());
    private final ConcurrentHashMap<String, List<Long>> failedLoginAttemptIPs = new ConcurrentHashMap<>(new HashMap<>());
    private final ConcurrentHashMap<String, AtomicInteger> failedLoginAttemptPersistent = new ConcurrentHashMap<>(new HashMap<>());
    private final SQLManager sqlManager;
    private final EmailChallenger emailChallenger;
    private final Map<String, Endpoint> endpoints;

    public PointsWebServer(int port, SQLManager sqlManager, EmailChallenger emailChallenger, int numberOfProxies) {
        super(port, numberOfProxies, 10000, new RateLimitParams(20, 5000), false, "http://localhost:3000", "https://ks-hl.github.io");

        this.sqlManager = sqlManager;
        this.emailChallenger = emailChallenger;
        this.endpoints = makeEndpointMap();
    }

    private Map<String, Endpoint> makeEndpointMap() {
        Map<String, Endpoint> endpointMap = new HashMap<>();

        endpointMap.put("/login", new Endpoint(Endpoint.AuthenticationStage.NONE, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            String username = request.bodyJSONOrEmpty().optString("username");
            String password = request.bodyJSONOrEmpty().optString("password");
            if (username == null || password == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No login info provided.");
            }
            Supplier<WebException> badUsernamePassword = () -> new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid username/password");
            logBuilder.append("\nLogin attempt from ").append(request.sender()).append(" as ").append(username);
            int uid = sqlManager.getUIDManager().getIDOpt(username, false).orElseThrow(badUsernamePassword);
            logBuilder.append(" (UID=").append(uid).append(")");

            loginLock.removeIfValues(l -> request.requestTime() - l.lastLocked > 60000);
            LoginLocker lock = loginLock.computeIfAbsent(username.toLowerCase(), u -> new LoginLocker());
            if (lock.tryLock(3000)) {
                try {
                    failedLoginAttemptPersistent.computeIfAbsent(request.sender(), s -> new AtomicInteger()).incrementAndGet();
                    ThrowingConsumer<HashMap<String, List<Long>>, WebException> failedConsumer = failedLoginAttemptUsernames -> {
                        var chain = failedLoginAttemptUsernames.computeIfAbsent(username.toLowerCase(), u -> new ArrayList<>());
                        chain.removeIf(l -> request.requestTime() - l > 60000L * 5);
                        int within5Minutes = chain.size();
                        int within1Minute = (int) chain.stream().filter(l -> request.requestTime() - l <= 60000L).count();
                        if (within1Minute >= 3) {
                            throw new WebException(HTTPResponseCode.FORBIDDEN, "Too many login attempts within 1 minute.");
                        }
                        if (within5Minutes >= 5) {
                            throw new WebException(HTTPResponseCode.FORBIDDEN, "Too many login attempts within 5 minutes.");
                        }
                    };
                    failedLoginAttemptUsernames.consumeThrowing(failedConsumer, 3000);
                    failedLoginAttemptIPs.consumeThrowing(failedConsumer, 3000);

                    if (uid > 0 && sqlManager.getPasswordManager().testPassword(uid, password)) {
                        failedLoginAttemptUsernames.remove(username.toLowerCase());
                        failedLoginAttemptIPs.remove(request.sender());
                        failedLoginAttemptPersistent.remove(request.sender());
                        return issueSession(uid, username, request.sender());
                    }
                    logBuilder.append("\nIncorrect password");
                    failedLoginAttemptUsernames.computeIfAbsent(username.toLowerCase(), u -> new ArrayList<>()).add(request.requestTime());
                    failedLoginAttemptIPs.computeIfAbsent(request.sender(), u -> new ArrayList<>()).add(request.requestTime());

                    if (failedLoginAttemptPersistent.get(request.sender()).get() > 30) {
                        sqlManager.getBannedIPManager().add(sqlManager.getIPIDManager().getIDOpt(request.sender(), true).orElseThrow());
                        logBuilder.append("\nIP BANNED");
                    }
                } finally {
                    try {
                        long requiredWait = getLoginDelayTime() - (System.currentTimeMillis() - request.requestTime());
                        if (requiredWait > 0) Thread.sleep(requiredWait);
                    } finally {
                        lock.unlock();
                    }
                }
            }
            throw badUsernamePassword.get();
        }));
        endpointMap.put("/createaccount", new Endpoint(Endpoint.AuthenticationStage.NONE, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            String email = request.bodyJSONOrEmpty().optString("email");
            String username = request.bodyJSONOrEmpty().optString("username");
            String password = request.bodyJSONOrEmpty().optString("password");
            if (username == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No login info provided.");
            }

            PasswordRequirements.validatePassword(password);

            Supplier<WebException> invalidEmail = () -> new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid email");

            if (email == null) {
                throw invalidEmail.get();
            }
            if (!MailHelper.isValidEmailAddress(email)) {
                throw invalidEmail.get();
            }

            boolean whitelisted = sqlManager.getEmailWhitelistManager().contains(sqlManager.getEmailIDManager().getIDOpt(email, false).orElse(null));

            if (!whitelisted || sqlManager.isEmailInUse(email)) {
                throw invalidEmail.get();
            }
            if (sqlManager.isEmailInUse(email)) {
                throw invalidEmail.get();
            }

            if (username.length() < 4) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Username must be at least 4 characters");
            }

            if (username.length() > 20) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Username must be no more than 20 characters");
            }

            if (!username.matches("[a-zA-Z0-9_]+")) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Username must be alphanumeric or underscores");
            }

            int uid = -1;
            try {
                uid = sqlManager.getUIDManager().getIDRequireNew(username);
            } catch (SQLException e) {
                if (!ConnectionManager.isConstraintViolation(e)) throw e;
            }
            if (uid <= 0) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "That username is already in use");
            }

            logBuilder.append(String.format("\nCreating new account: UID=%s, username=%s, email=%s", uid, username, email));

            sqlManager.getPasswordManager().setPassword(uid, password, 0);

            sqlManager.createAccount(uid, email);

            emailChallenger.startChallenge(uid, email, false);

            return issueSession(uid, username, request.sender());
        }));
        endpointMap.put("/forgotpassword", new Endpoint(Endpoint.AuthenticationStage.NONE, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            String email = request.query().get("email");
            if (email == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No email provided");
            }
            if (!MailHelper.isValidEmailAddress(email)) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid email");
            }

            boolean whitelisted = sqlManager.getEmailWhitelistManager().contains(sqlManager.getEmailIDManager().getIDOpt(email, false).orElse(null));

            Optional<Integer> uid = sqlManager.getUID(email);

            if (whitelisted && uid.isPresent()) {
                emailChallenger.startChallenge(uid.get(), email, true);
                logBuilder.append("\nRequested a password reset for ").append(email).append(", uid=").append(uid);
            } else {
                logBuilder.append("\nRequested a password reset for invalid email: ").append(email);
                failedLoginAttemptPersistent.computeIfAbsent(request.sender(), s -> new AtomicInteger()).incrementAndGet();
                if (failedLoginAttemptPersistent.get(request.sender()).get() > 30) {
                    logBuilder.append(", IP BANNED");
                    sqlManager.getBannedIPManager().add(sqlManager.getIPIDManager().getIDOpt(request.sender(), true).orElseThrow());
                }
            }
            long sleepRequired = 3000;
            sleepRequired -= System.currentTimeMillis() - request.requestTime();
            if (sleepRequired > 0) Thread.sleep(sleepRequired);
            return new Response().body(new JSONObject().put("success", true));
        }));
        endpointMap.put("/resetpassword", new Endpoint(Endpoint.AuthenticationStage.NONE, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            if (request.type() != HTTPRequestType.POST || request.bodyJSON() == null) {
                throw new WebException(HTTPResponseCode.BAD_REQUEST, "resetpassword should be POST");
            }

            String code = request.bodyJSON().optString("code");
            if (code == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No user provided.");
            }
            int uid = emailChallenger.completeResetChallenge(code);
            logBuilder.append("\nReset UID ").append(uid);
            if (uid > 0) {
                String newPassword = request.bodyJSON().optString("new");
                PasswordRequirements.validatePassword(newPassword);
                sqlManager.getPasswordManager().setPassword(uid, newPassword, 0);

                return new Response().body(new JSONObject().put("success", true));
            } else {
                failedLoginAttemptPersistent.computeIfAbsent(request.sender(), s -> new AtomicInteger()).incrementAndGet();
                if (failedLoginAttemptPersistent.get(request.sender()).get() > 30) {
                    sqlManager.getBannedIPManager().add(sqlManager.getIPIDManager().getIDOpt(request.sender(), true).orElseThrow());
                    logBuilder.append(", IP BANNED");
                }
            }
            throw new WebException(HTTPResponseCode.FORBIDDEN);
        }));

        endpointMap.put("/emailcode", new Endpoint(Endpoint.AuthenticationStage.NEW_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            AuthResult authResult = endpointRequest.authResult();

            String code = request.query().get("code");
            if (code == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No code provided");
            }
            if (!code.matches("\\d{6,}")) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid code");
            }
            if (emailChallenger.completeChallenge(authResult.uid(), code)) {
                sqlManager.getValidatedAccountsManager().add(authResult.uid());
                return new Response().body(new JSONObject().put("success", true));
            }
            throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid or expired code");

        }));
        endpointMap.put("/newcode", new Endpoint(Endpoint.AuthenticationStage.NEW_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            AuthResult authResult = endpointRequest.authResult();

            emailChallenger.startChallenge(authResult.uid(), sqlManager.getEmail(authResult.uid()), false);
            return new Response().body(new JSONObject().put("success", true));
        }));

        endpointMap.put("/", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.GET, endpointRequest -> {
            AuthResult authResult = endpointRequest.authResult();

            JSONObject body = new JSONObject();
            body.put("success", true);
            body.put("admin", authResult.admin());

            JSONObject settings = new JSONObject();
            for (SQLManager.Setting setting : SQLManager.Setting.values()) {
                settings.put(setting.toString(), setting.toJSON(sqlManager, authResult.uid()));
            }

            body.put("settings", settings);
            return new Response().body(body);
        }));
        endpointMap.put("/logout", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            AuthResult authResult = endpointRequest.authResult();

            boolean success = sqlManager.logout(authResult.uid(), authResult.token_id());
            return new Response().body(new JSONObject().put("success", success)).header("Set-Cookie", "session=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        }));
        endpointMap.put("/logouteverywhere", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            AuthResult authResult = endpointRequest.authResult();

            sqlManager.logoutEverywhere(authResult.uid());
            return new Response().body(new JSONObject().put("success", true)).header("Set-Cookie", "session=; expires=Thu, 01 Jan 1970 00:00:00 GMT");
        }));
        endpointMap.put("/changepassword", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            AuthResult authResult = endpointRequest.authResult();

            String currentPassword = request.bodyJSONOrEmpty().optString("current");
            if (currentPassword == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No password provided.");
            }

            if (!sqlManager.getPasswordManager().testPassword(authResult.uid, currentPassword)) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid current password");
            }
            String newPassword = request.bodyJSONOrEmpty().optString("new");
            PasswordRequirements.validatePassword(newPassword);

            if (currentPassword.equalsIgnoreCase(newPassword)) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Your passwords are the same");
            }

            sqlManager.getPasswordManager().setPassword(authResult.uid, newPassword, 0);
            return new Response().body(new JSONObject().put("success", true));

        }));
        endpointMap.put("/points/list", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.GET, endpointRequest -> {
            AuthResult authResult = endpointRequest.authResult();

            return new Response().body(new JSONObject().put("points", sqlManager.getPeople(authResult.uid()).stream().map(SQLManager.Person::toJSON).collect(JSONCollector.toJSON())));
        }));
        endpointMap.put("/points/new", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            StringBuilder logBuilder = endpointRequest.logBuilder();
            AuthResult authResult = endpointRequest.authResult();

            SQLManager.Person person = sqlManager.add(authResult.uid(), "");
            logBuilder.append("\nCreated ").append(person);
            return new Response().body(person.toJSON());
        }));
        endpointMap.put("/settings/set", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();
            AuthResult authResult = endpointRequest.authResult();

            String settingString = request.bodyJSONOrEmpty().optString("key");
            SQLManager.Setting setting;
            try {
                setting = SQLManager.Setting.valueOf(settingString == null ? null : settingString.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No setting specified");
            }
            try {
                Object value = request.bodyJSONOrEmpty().opt("value");
                if (value == null) {
                    throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No value provided");
                }
                try {
                    setting.getSettingManager(sqlManager).setFromObject(authResult.uid(), value);
                    logBuilder.append(String.format("\nSet %s to %s", settingString, value));
                    return new Response().body(new JSONObject().put("setting", setting.toJSON(sqlManager, authResult.uid())));
                } catch (SettingManager.ArgumentValidationException e) {
                    throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, e.getMessage());
                } catch (ClassCastException e) {
                    logBuilder.append("\n").append(e.getMessage());
                    throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid type for setting");
                }
            } catch (WebException e) {
                return new Response().code(e.responseCode).body(new JSONObject().put("error", e.getUserErrorMessage()).put("setting", setting.toJSON(sqlManager, authResult.uid())));
            }
        }));
        endpointMap.put("/points/set/name", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            AuthResult authResult = endpointRequest.authResult();
            int id = endpointRequest.getPointIDFromQuery();

            String name = request.query().get("name");
            if (name == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Name can not be null");
            }

            if (sqlManager.setName(authResult.uid(), id, name)) {
                return new Response().body(new JSONObject().put("success", true));
            } else {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Points not found");
            }
        }));
        endpointMap.put("/points/set/points", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            AuthResult authResult = endpointRequest.authResult();
            int id = endpointRequest.getPointIDFromQuery();

            String pointsStr = request.query().get("points");
            int points;
            try {
                points = Integer.parseInt(pointsStr);
            } catch (IllegalArgumentException ignored) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid points");
            }

            if (points < 0) {
                if (!SQLManager.Setting.ALLOW_NEGATIVE.getSettingManager(sqlManager).getBoolean(authResult.uid())) {
                    int currentPoints = sqlManager.getPerson(authResult.uid(), id).orElseThrow(() -> new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Points not found")).points();
                    if (points < currentPoints) { // Allows raising points from a negative value regardless of the setting
                        throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Setting to allow negative is disabled");
                    }
                }
            }

            if (sqlManager.setPoints(authResult.uid(), id, points)) {
                return new Response().body(new JSONObject().put("success", true));
            } else {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Points not found");
            }
        }));
        endpointMap.put("/points/delete", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            StringBuilder logBuilder = endpointRequest.logBuilder();
            AuthResult authResult = endpointRequest.authResult();
            int id = endpointRequest.getPointIDFromQuery();

            boolean deleted = sqlManager.remove(authResult.uid(), id);
            if (deleted) {
                logBuilder.append("\nDeleted point ").append(id);
            } else {
                logBuilder.append("\nPoint not found");
            }
            return new Response().body(new JSONObject().put("success", deleted));
        }));
        endpointMap.put("/points/set/priority", new Endpoint(Endpoint.AuthenticationStage.VALIDATED_ACCOUNT, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            AuthResult authResult = endpointRequest.authResult();
            int id = endpointRequest.getPointIDFromQuery();

            boolean up;
            try {
                up = Boolean.parseBoolean(request.query().get("up"));
            } catch (IllegalArgumentException e) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "'up' must be 'true' or 'false'");
            }
            return new Response().body(new JSONObject().put("success", sqlManager.setPriority(authResult.uid(), id, up)));
        }));

        endpointMap.put("/makedemo", new Endpoint(Endpoint.AuthenticationStage.ADMIN, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            String user = request.bodyJSONOrEmpty().optString("user");
            if (user == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No user provided.");
            }

            String newPassword = request.bodyJSONOrEmpty().optString("new");
            PasswordRequirements.validatePassword(newPassword);

            logBuilder.append("\nMaking new demo account: ").append(user);

            int uid = -1;
            try {
                uid = sqlManager.getUIDManager().getIDRequireNew(user);
            } catch (SQLException e) {
                if (!ConnectionManager.isConstraintViolation(e)) throw e;
            }
            if (uid <= 0) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "That username is already in use");
            }

            String email = user + "@demo.com";

            logBuilder.append(String.format("\nCreating new account: UID=%s, username=%s, email=%s", uid, user, email));

            sqlManager.getPasswordManager().setPassword(uid, newPassword, 0);
            sqlManager.createAccount(uid, email);
            sqlManager.getValidatedAccountsManager().add(uid);

            return new Response().body(new JSONObject().put("success", true));
        }));
        endpointMap.put("/setpassword", new Endpoint(Endpoint.AuthenticationStage.ADMIN, HTTPRequestType.POST, endpointRequest -> {
            Request request = endpointRequest.request();
            StringBuilder logBuilder = endpointRequest.logBuilder();

            String user = request.bodyJSONOrEmpty().optString("user");
            if (user == null) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "No user provided.");
            }
            int targetUID = sqlManager.getUIDManager().getIDOpt(user, false).orElseThrow(() -> new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "User not found."));
            if (sqlManager.getAdminManager().contains(targetUID)) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Can't set passwords for admins");
            }
            logBuilder.append("\nSetting password for ").append(user);

            String newPassword = request.bodyJSONOrEmpty().optString("new");
            PasswordRequirements.validatePassword(newPassword);

            sqlManager.getPasswordManager().setPassword(targetUID, newPassword, 0);
            failedLoginAttemptIPs.clear(); // Not sure if there's a better way, we don't know the user's IP very easily. This shouldn't be an issue because this endpoint will rarely be called.
            failedLoginAttemptUsernames.remove(user.toLowerCase());
            return new Response().body(new JSONObject().put("success", true));

        }));

        return Collections.unmodifiableMap(endpointMap);
    }

    private static class Endpoint {
        private final AuthenticationStage authStage;
        private final HTTPRequestType requestType;
        private final EndpointHandler endpointHandler;

        public enum AuthenticationStage {NONE, NEW_ACCOUNT, VALIDATED_ACCOUNT, ADMIN}

        @FunctionalInterface
        public interface EndpointHandler {
            Response handle(EndpointRequest endpointRequest) throws Exception;
        }

        Endpoint(AuthenticationStage authStage, HTTPRequestType requestType, EndpointHandler endpointHandler) {
            this.authStage = authStage;
            this.requestType = requestType;
            this.endpointHandler = endpointHandler;
        }
    }

    public record EndpointRequest(Request request, AuthResult authResult, StringBuilder logBuilder) {
        public int getPointIDFromQuery() throws WebException {
            String idStr = request.query().get("id");
            int id = -1;
            try {
                if (idStr != null) id = Integer.parseInt(idStr);
            } catch (IllegalArgumentException ignored) {
            }
            if (id <= 0) {
                throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, "Invalid ID");
            }
            return id;
        }
    }

    @Override
    public void print(String s, Throwable throwable) {
        Main.print(s, throwable);
    }

    @Override
    public void info(String s) {
        Main.info(s);
    }

    @Override
    public void warning(String s) {
        Main.warning(s);
    }

    private final Map<Request, StringBuilder> requestLoggingMap = new HashMap<>();

    @Override
    protected Response handle(Request request) throws WebException {
        StringBuilder logBuilder = new StringBuilder();
        try {
            int ipID = sqlManager.getIPIDManager().getIDOpt(request.sender(), true).orElseThrow();
            if (sqlManager.getBannedIPManager().contains(ipID)) {
                throw new WebException(HTTPResponseCode.FORBIDDEN, "Your IP is banned. Please contact an administrator.");
            }

            AuthResult authResult = validateSessionCookie(request);
            if (authResult.uid() > 0 && authResult.valid()) {
                String user;
                try {
                    user = sqlManager.getUIDManager().getValueOpt(authResult.uid()).orElse("Unknown");
                } catch (SQLException | BusyException e) {
                    user = "Unknown User";
                    Main.print("An error occurred searching user for UID " + authResult.uid, e);
                }
                logBuilder.append(String.format(" (%s, %s)", user, authResult));
            } else {
                logBuilder.append(" (Not Authenticated)");
            }

            final String endpointString = (request.endpoint().startsWith("/api/") ? request.endpoint().substring(4) : request.endpoint()).toLowerCase();
            Endpoint endpoint = this.endpoints.get(endpointString);
            if (endpoint != null) {
                if (endpoint.authStage == Endpoint.AuthenticationStage.NONE) {
                    if (authResult.valid()) {
                        throw new WebException(HTTPResponseCode.BAD_REQUEST, "You are already logged in!");
                    }
                } else {
                    if (!authResult.valid()) {
                        throw new WebException(HTTPResponseCode.FORBIDDEN, "Not authenticated");
                    }

                    switch (endpoint.authStage) {
                        case NEW_ACCOUNT -> {
                            if (authResult.isAccountEmailValidated()) {
                                throw new WebException(HTTPResponseCode.FORBIDDEN, "Your account is already activated.");
                            }
                        }
                        case VALIDATED_ACCOUNT -> {
                            if (!authResult.isAccountEmailValidated()) {
                                throw new WebException(HTTPResponseCode.FORBIDDEN, "Please activate your account. Check your email.");
                            }
                        }
                        case ADMIN -> {
                            if (!authResult.admin()) {
                                throw new ForbiddenAdminCommandException();
                            }
                        }
                    }
                }
                if (request.type() != endpoint.requestType) {
                    throw new WebException(HTTPResponseCode.UNPROCESSABLE_ENTITY, endpointString + " must be " + endpoint.requestType);
                }

                return endpoint.endpointHandler.handle(new EndpointRequest(request, authResult, logBuilder));
            }

            return null;
        } catch (WebException e) {
            throw e;
        } catch (BusyException e) {
            throw new WebException(HTTPResponseCode.SERVICE_UNAVAILABLE);
        } catch (Throwable t) {
            Main.print("An error occurred with endpoint " + request.endpoint(), t);
            throw new WebException(HTTPResponseCode.INTERNAL_SERVER_ERROR);
        } finally {
            synchronized (requestLoggingMap) {
                requestLoggingMap.put(request, logBuilder);
            }
        }
    }

    @Override
    protected void logRequest(Request request, Response response, String msg) {
        synchronized (requestLoggingMap) {
            StringBuilder stringBuilder = requestLoggingMap.remove(request);
            if (stringBuilder != null) {
                msg = msg.trim() + stringBuilder;
            }
            requestLoggingMap.keySet().removeIf(r -> System.currentTimeMillis() - r.requestTime() > 10000);
        }
        super.logRequest(request, response, msg);
    }

    public static class ForbiddenAdminCommandException extends WebException {
        public ForbiddenAdminCommandException() {
            super(HTTPResponseCode.NOT_FOUND);
        }
    }


    private Response issueSession(int uid, String name, String ip) throws SQLException, BusyException {
        SQLSessionTokenManager.SessionToken sessionToken = sqlManager.getTokenManager().generateNew(uid, ip);
        Main.info(name + " (" + ip + ") successfully authenticated. TokenID=" + sessionToken.token_id());
        String cookie = "session=" + sessionToken.token_id() + ":" + sessionToken.token() + "; " //
                + "HttpOnly; " //
                + "Max-Age=" + sqlManager.getTokenManager().getSessionDuration() / 1000 + "; " //
                + "SameSite=Lax; " //
                + "Secure; ";
        Response response = new Response();
        response.header("Set-Cookie", cookie);
        response.body(new JSONObject().put("success", true));
        return response;
    }

    AuthResult validateSessionCookie(Request request) throws WebException {
        List<String> cookie = request.headers().get("Cookie");
        if (cookie == null || cookie.isEmpty()) return AuthResult.fail();

        String session = null;
        for (String s1 : cookie) {
            for (String s2 : s1.split(";")) {
                s2 = s2.trim();
                if (s2.startsWith("session=")) {
                    session = s2.substring(8);
                }
            }
        }

        if (session == null) return AuthResult.fail();

        String[] parts = session.split(":");
        int token_id;
        try {
            token_id = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            return AuthResult.fail();
        }

        try {
            int uid = sqlManager.getTokenManager().test(token_id, parts[1], request.sender());
            if (uid > 0) {
                boolean admin = sqlManager.getAdminManager().contains(uid);
                boolean validated = sqlManager.getValidatedAccountsManager().contains(uid);
                return new AuthResult(true, uid, token_id, admin, validated); // AUTHENTICATED
            }
        } catch (SQLException e) {
            Main.print("An error occurred while testing session key for " + request.sender() + "/" + parts[0], e);
            throw new WebException(HTTPResponseCode.INTERNAL_SERVER_ERROR);
        } catch (BusyException e) {
            throw new WebException(HTTPResponseCode.SERVICE_UNAVAILABLE);
        }

        return AuthResult.fail();
    }

    record AuthResult(boolean valid, int uid, int token_id, boolean admin, boolean isAccountEmailValidated) {
        @Override
        public String toString() {
            if (!valid || uid <= 0) return "AuthResult[Invalid]";
            return uid + ", TKN:" + token_id() + (admin() ? ", ADMIN" : "");
        }

        public static AuthResult fail() {
            return new AuthResult(false, -1, -1, false, false);
        }

    }

    protected long getLoginDelayTime() {
        return LOGIN_DELAY_TIME;
    }
}
