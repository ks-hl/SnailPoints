package dev.kshl.points;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class MailHelper {
    public static final Pattern EMAIL_PATTERN = Pattern.compile("(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)])");
    private final Mailer mailer;
    private final String from;

    public MailHelper(String host, int port, String from, String password) {
        this.mailer = MailerBuilder //
                .withSMTPServer(host, port, from, password) //
                .withTransportStrategy(TransportStrategy.SMTP_TLS) //
                .withSessionTimeout(10 * 1000) //
                .clearEmailValidator() // turns off email validation
//                .withDebugLogging(true) //
                .buildMailer();
        this.from = from;
    }

    public void sendMail(String to, String subject, String body) throws Exception {
        sendMail(to, subject, b -> b.withPlainText(body));
    }

    public void sendMailHTML(String to, String subject, String html) throws Exception {
        sendMail(to, subject, b -> b.withHTMLText(html));
    }

    public void sendMail(String to, String subject, Consumer<EmailPopulatingBuilder> modify) throws Exception {
        if (!isValidEmailAddress(to)) {
            throw new IllegalArgumentException("Invalid email address");
        }
        var builder = EmailBuilder.startingBlank().from(from).to(to).withSubject(subject);
        modify.accept(builder);
        Email email = builder.buildEmail();

        mailer.sendMail(email).get();
    }

    public static boolean isValidEmailAddress(String email) {
        if (email == null) return false;
        return EMAIL_PATTERN.matcher(email).matches();
    }
}
