package dev.kshl.points;

import dev.kshl.kshlib.encryption.CodeGenerator;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class EmailChallenger {
    private final MailHelper mailHelper;
    private final Map<Integer, Code> codes = new HashMap<>();
    private final Map<String, ResetCode> resetCodes = new HashMap<>();
    private final Map<Integer, AtomicInteger> failures = new HashMap<>();
    private final Map<String, Long> lastEmailSent = new HashMap<>();

    private static final String EMAIL_FORMAT = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Verification Code</title>
                <style>
                    body {
                        font-family: Arial, sans-serif;
                        background-color: #f4f4f4;
                        margin: 0;
                        padding: 20px;
                    }
                    .container {
                        background-color: #ffffff;
                        padding: 20px;
                        border-radius: 10px;
                        box-shadow: 0 0 10px rgba(0, 0, 0, 0.1);
                        max-width: 600px;
                        margin: 0 auto;
                    }
                    .header {
                        text-align: center;
                        padding-bottom: 20px;
                    }
                    .code {
                        font-size: 24px;
                        font-weight: bold;
                        color: #4CAF50;
                    }
                    .footer {
                        text-align: center;
                        padding-top: 20px;
                        font-size: 12px;
                        color: #888888;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    %s
                    <p>This code will expire in 5 minutes.</p>
                    <p>If you did not request this code, please ignore this message.</p>
                    <div class="footer">
                        <p>Thank you,<br>SnailPoints</p>
                    </div>
                </body>
            </html>
            """;
    private static final String VERIFICATION_EMAIL_FORMAT = String.format(EMAIL_FORMAT, """
            <div class="header">
                <h2>Your Verification Code</h2>
            </div>
            <p>Hello,</p>
            <p>Your verification code is:</p>
            <p class="code"><code>%s</code></p>""");
    private static final String PASSWORD_RESET_EMAIL_FORMAT = String.format(EMAIL_FORMAT, """
            <div class="header">
                <h2>Your Password Reset Code</h2>
            </div>
            <p>Hello,</p>
            <p>You or someone else attempted to reset your password for SnailPoints. If this was you, click the link below to change your password.</p>
            <p class="code"><code><a href=https://snailpoints.com/reset?code=%s>https://snailpoints.com/reset?code=%s</a></code></p>""");

    public EmailChallenger(MailHelper mailHelper) {
        this.mailHelper = mailHelper;
    }

    public void startChallenge(int uid, String email, boolean resetPassword) throws Exception {
        synchronized (lastEmailSent) {
            final long now = System.currentTimeMillis();
            lastEmailSent.values().removeIf(time -> now - time > 300000L);
            Long lastSend = lastEmailSent.get(email.toLowerCase());
            if (lastSend != null) {
                throw new IllegalArgumentException("Can't send another email for " + (300000L - (now - lastSend)) / 1000 + " seconds.");
            }
            lastEmailSent.put(email.toLowerCase(), now);
        }
        StringBuilder code = new StringBuilder();
        if (resetPassword) {
            code.append(CodeGenerator.generateSecret(30, true, true, false));
        } else {
            SecureRandom secureRandom = new SecureRandom();
            for (int i = 0; i < 6; i++) {
                code.append(secureRandom.nextInt(0, 10));
            }
        }
        if (resetPassword) {
            resetCodes.put(code.toString(), new ResetCode(uid, System.currentTimeMillis() + 300000));
        } else {
            codes.put(uid, new Code(code.toString(), System.currentTimeMillis() + 300000));
        }
        mailHelper.sendMailHTML(email,
                resetPassword ? "SnailPoints Password Reset" : "SnailPoints Account Verification",
                (resetPassword ? PASSWORD_RESET_EMAIL_FORMAT : VERIFICATION_EMAIL_FORMAT).replace("%s", code));
    }

    public boolean completeChallenge(int uid, String code) {
        codes.values().removeIf(c -> System.currentTimeMillis() >= c.expires());
        Code theCode = codes.get(uid);
        AtomicInteger failureCount = this.failures.computeIfAbsent(uid, u -> new AtomicInteger());
        if (failureCount.getAndIncrement() > 3) {
            codes.remove(uid);
            failures.remove(uid);
            return false;
        }
        if (theCode == null) return false;
        if (!theCode.code().equals(code)) return false;
        codes.remove(uid);
        failures.remove(uid);
        return true;
    }

    public int completeResetChallenge(String code) {
        resetCodes.values().removeIf(c -> System.currentTimeMillis() >= c.expires());
        ResetCode resetCode = resetCodes.remove(code);
        if (resetCode == null) return -1;
        return resetCode.uid();
    }

    public record Code(String code, long expires) {
    }

    public record ResetCode(int uid, long expires) {
    }
}
