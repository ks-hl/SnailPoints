package dev.kshl.points;

import dev.kshl.kshlib.exceptions.BusyException;
import dev.kshl.kshlib.sql.ConnectionManager;
import dev.kshl.kshlib.sql.SQLIDManager;
import dev.kshl.kshlib.sql.SQLPasswordManager;
import dev.kshl.kshlib.sql.SQLSessionTokenManager;
import dev.kshl.kshlib.sql.SQLSet;
import dev.kshl.kshlib.sql.SettingManager;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class SQLManager extends ConnectionManager {
    private final SQLSessionTokenManager tokenManager;
    private final SQLPasswordManager passwordManager;
    private final SQLIDManager.Str uidManager;
    private final SQLIDManager.Str ipIDManager;
    private final SQLIDManager.Str emailIDManager;
    private final SQLSet.Int bannedIPManager;
    private final SQLSet.Int validatedAccountsManager;
    private final SQLSet.Int adminManager;
    private final SQLSet.Int emailWhitelistManager;
    private final SettingManager.Bool allowNegativePointsSetting = new SettingManager.Bool(this, "setting_allow_negative", true);
    private final SettingManager.Int redeemCostSetting = new SettingManager.Int(this, "setting_redeem_cost", 20) {
        @Override
        public void validate(Integer value) throws ArgumentValidationException {
            if (value <= 0) {
                throw new ArgumentValidationException("Value must be > 0");
            }
            if (value > 1000) {
                throw new ArgumentValidationException("Value must be <= 1000");
            }
        }
    };

    public enum Setting {
        ALLOW_NEGATIVE, REDEEM_COST;

        public SettingManager<?> getSettingManager(SQLManager sqlManager) {
            return switch (this) {
                case ALLOW_NEGATIVE -> sqlManager.allowNegativePointsSetting;
                case REDEEM_COST -> sqlManager.redeemCostSetting;
            };
        }

        public String getFormatted() {
            return switch (this) {
                case ALLOW_NEGATIVE -> "Allow Negative Points";
                case REDEEM_COST -> "Redeem Cost";
            };
        }

        public JSONObject toJSON(SQLManager sqlManager, int uid) throws SQLException, BusyException {
            return new JSONObject()
                    .put("key", toString())
                    .put("value", getSettingManager(sqlManager).get(uid))
                    .put("formatted", getFormatted());
        }
    }

    public SQLManager(File sqliteFile) throws IOException, SQLException, ClassNotFoundException {
        super(sqliteFile);

        try {
            tokenManager = new SQLSessionTokenManager(this, "sessions", 3600000L * 24 * 7, true);
            passwordManager = new SQLPasswordManager(this, "passwords", SQLPasswordManager.Type.PASSWORD);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        uidManager = new SQLIDManager.Str(this, "uids");
        ipIDManager = new SQLIDManager.Str(this, "ips");
        emailIDManager = new SQLIDManager.Str(this, "email_ids");
        bannedIPManager = new SQLSet.Int(this, "banned_ips", true);
        validatedAccountsManager = new SQLSet.Int(this, "validated_accounts", true);
        adminManager = new SQLSet.Int(this, "admins", true);
        emailWhitelistManager = new SQLSet.Int(this, "email_whitelist", true);
    }

    @Override
    protected void init(Connection connection) throws SQLException {
        tokenManager.init(connection);
        passwordManager.init(connection);
        uidManager.init(connection);
        ipIDManager.init(connection);
        emailIDManager.init(connection);
        bannedIPManager.init(connection);
        validatedAccountsManager.init(connection);
        adminManager.init(connection);
        emailWhitelistManager.init(connection);

        for (Setting setting : Setting.values()) {
            setting.getSettingManager(this).init(connection);
        }

        execute(connection, "CREATE TABLE IF NOT EXISTS points (id INTEGER PRIMARY KEY " + autoincrement() + ", uid INT, name TEXT, points INT, priority INT)");
        execute(connection, "CREATE TABLE IF NOT EXISTS accounts (time_created BIGINT, uid INT PRIMARY KEY, email_id INT, admin BOOLEAN, UNIQUE(email_id))");
//        try {
//            query(connection, "SELECT email FROM accounts", rs -> {
//                while (rs.next()) {
//                    String email = rs.getString(1);
//                    int emailID = emailIDManager.getIDOpt(email, true).orElse(-1);
//                    if (emailID <= 0) {
//                        Main.warning("Failed to migrate email " + email);
//                        continue;
//                    }
//                    execute(connection, "UPDATE accounts SET email_id=? WHERE email=?", emailID, email);
//                }
//            });
//            execute(connection, "CREATE UNIQUE INDEX accounts_unique_email_id ON accounts(email_id)");
//        } catch (SQLException e) {
//            if (!e.getMessage().contains("duplicate column name") && !e.getMessage().contains("no such column")) throw e;
//        }
    }

    public Person add(int uid, String name) throws SQLException, BusyException {
        int id = executeReturnGenerated("INSERT INTO points (uid, name, points) VALUES (?,?,0)", 3000, uid, name);
        execute("UPDATE points SET priority=id WHERE uid=? AND id=?", 3000L, uid, id);
        return new Person(id, name, 0);
    }

    public boolean remove(int uid, int id) throws SQLException, BusyException {
        return executeReturnRows("DELETE FROM points WHERE id=? AND uid=?", 3000, id, uid) > 0;
    }

    public boolean setPoints(int uid, int id, int points) throws SQLException, BusyException {
        return executeReturnRows("UPDATE points SET points=? WHERE id=? AND uid=?", 3000, points, id, uid) > 0;
    }

    public boolean setName(int uid, int id, String name) throws SQLException, BusyException {
        return executeReturnRows("UPDATE points SET name=? WHERE id=? AND uid=?", 3000, name, id, uid) > 0;
    }

    public boolean deleteAccount(int uid) throws SQLException, BusyException {
        if (!getUIDManager().remove(uid)) {
            return false;
        }
        execute("DELETE FROM points WHERE uid=?", 3000, uid);
        getTokenManager().remove(uid);
        getPasswordManager().remove(uid);

        //TODO remove from anywhere else?
        return true;
    }

    public boolean logout(int uid, int token_id) throws SQLException, BusyException {
        return getTokenManager().remove(uid, token_id);
    }

    public boolean logoutEverywhere(int uid) throws SQLException, BusyException {
        return getTokenManager().remove(uid);
    }

    public List<Person> getPeople(int uid) throws SQLException, BusyException {
        List<Person> out = new ArrayList<>();
        query("SELECT * FROM POINTS WHERE uid=? ORDER BY priority ASC", rs -> {
            while (rs.next()) {
                out.add(new Person(rs.getInt("id"), rs.getString("name"), rs.getInt("points")));
            }
        }, 3000, uid);
        return out;
    }

    @Override
    protected void debug(String s) {

    }

    @Override
    protected boolean checkAsync() {
        return true;
    }

    public boolean isEmailInUse(String email) throws SQLException, BusyException {
        return getUID(email).isPresent();
    }

    public Optional<Integer> getUID(String email) throws SQLException, BusyException {
        Optional<Integer> emailID = emailIDManager.getIDOpt(email, false);
        if (emailID.isEmpty()) return Optional.empty();
        return query("SELECT uid FROM accounts WHERE email_id=?", rs -> {
            if (!rs.next()) return Optional.empty();
            return Optional.of(rs.getInt(1));
        }, 3000, emailID.get());
    }

    public void createAccount(int uid, String email) throws SQLException, BusyException {
        execute("INSERT INTO accounts (time_created, uid, email_id) VALUES (?,?,?)", 10000L, System.currentTimeMillis(), uid, emailIDManager.getIDOpt(email, true).orElseThrow());
    }

    public String getEmail(int uid) throws SQLException, BusyException {
        return query("SELECT email_id FROM accounts WHERE uid=?", rs -> {
            if (!rs.next()) return null;
            return emailIDManager.getValueOpt(rs.getInt(1)).orElse(null);
        }, 3000, uid);
    }

    public boolean setPriority(int uid, int id, boolean up) throws SQLException, BusyException {
        return executeTransaction(connection -> {
            // Fetch current priority of the target entry
            int currentPriority = query(connection, "SELECT priority FROM points WHERE uid=? AND id=?", rs -> {
                if (!rs.next()) return -1;
                return rs.getInt(1);
            }, uid, id);

            // Fetch the adjacent entry's priority and id
            String getAdjacentEntrySql = "SELECT id, priority FROM points WHERE uid=? AND priority";
            if (up) {
                getAdjacentEntrySql += "<? ORDER BY priority DESC LIMIT 1";
            } else {
                getAdjacentEntrySql += ">? ORDER BY priority ASC LIMIT 1";
            }
            int[] adjacent = query(connection, getAdjacentEntrySql, rs -> {
                if (!rs.next()) return null;
                return new int[]{rs.getInt(1), rs.getInt(2)};
            }, uid, currentPriority);

            if (adjacent == null) return false;

            int adjacentId = adjacent[0];
            int adjacentPriority = adjacent[1];

            // Swap the priorities
            String updatePrioritySql = "UPDATE points SET priority=? WHERE uid=? AND id=?";
            execute(connection, updatePrioritySql, adjacentPriority, uid, id);
            execute(connection, updatePrioritySql, currentPriority, uid, adjacentId);

            return true;

        }, 3000L);
    }


    public record Person(int id, String name, int points) {
        public JSONObject toJSON() {
            JSONObject out = new JSONObject();
            out.put("id", id);
            out.put("name", name);
            out.put("points", points);
            return out;
        }
    }

    @Override
    protected boolean isDebug() {
        return false;
    }

    public Optional<Person> getPerson(int uid, int id) throws SQLException, BusyException {
        return getPeople(uid).stream().filter(p -> p.id() == id).findAny();
    }

    public SQLSessionTokenManager getTokenManager() {
        return tokenManager;
    }

    public SQLPasswordManager getPasswordManager() {
        return passwordManager;
    }

    public SQLIDManager.Str getUIDManager() {
        return uidManager;
    }

    public SQLIDManager.Str getIPIDManager() {
        return ipIDManager;
    }

    public SQLIDManager.Str getEmailIDManager() {
        return emailIDManager;
    }

    public SQLSet.Int getBannedIPManager() {
        return bannedIPManager;
    }

    public SQLSet.Int getValidatedAccountsManager() {
        return validatedAccountsManager;
    }

    public SQLSet.Int getAdminManager() {
        return adminManager;
    }

    public SQLSet.Int getEmailWhitelistManager() {
        return emailWhitelistManager;
    }
}
