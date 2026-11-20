package io.github.rkaushikdev.cartwall.cli;

import io.github.rkaushikdev.cartwall.BroadcastMonth;
import io.github.rkaushikdev.cartwall.Money;
import io.github.rkaushikdev.cartwall.persistence.UnitOfWork;
import io.github.rkaushikdev.cartwall.persistence.models.CreditRow;
import io.github.rkaushikdev.cartwall.persistence.models.Reason;
import io.github.rkaushikdev.cartwall.services.Crediting;
import io.github.rkaushikdev.cartwall.services.Crediting.Standing;
import io.github.rkaushikdev.cartwall.services.Invoicing;
import io.github.rkaushikdev.cartwall.services.Invoicing.RunReport;
import io.github.rkaushikdev.cartwall.services.Invoicing.Skipped;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

/**
 * The billing commands, run once a month by a person who is watching. None of them go in cron:
 * the invoice run puts numbers in front of agencies, and a wrong one costs a phone call to every
 * account on the list.
 *
 * <p>{@code --dry-run} is not a flag on the service. The service raises invoices; the command rolls
 * the transaction back instead of committing it. That is the only honest dry run of something with
 * a gapless counter in it: any version that predicts the numbers without taking them is wrong the
 * first time two people run it at once.
 */
final class BillingCommands {

    private BillingCommands() {}

    private static LocalDate dayOr(LocalDate on) {
        return on != null ? on : LocalDate.now();
    }

    /** Raise the invoices for one broadcast month. */
    @Command(
            name = "invoice",
            mixinStandardHelpOptions = true,
            description = "Raise the invoices for broadcast MONTH, written as 2025-08.")
    static final class Invoice implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(paramLabel = "MONTH", description = "The broadcast month, written as 2025-08.")
        private String month;

        @Option(names = "--on", converter = Days.class, description = "Date to raise them on. Defaults to today.")
        private LocalDate on;

        @Option(names = "--dry-run", description = "Say what it would do and change nothing.")
        private boolean dryRun;

        @Override
        public Integer call() {
            BroadcastMonth period = BroadcastMonth.parse(month);
            PrintWriter out = main.terminal().out();
            RunReport report;
            try (UnitOfWork uow = main.work()) {
                report = Invoicing.runMonth(uow, period, dayOr(on));
                out.println(report.month() + "  " + period.start() + " to " + period.end());
                report.raised().forEach(number -> out.println("  raised   " + number));
                for (Skipped skipped : report.skipped()) {
                    out.println("  skipped  " + skipped.order() + " (" + skipped.why() + ")");
                }
                report.nothingAired().forEach(order -> out.println("  nothing  " + order));
                if (dryRun) {
                    out.println();
                    out.println("dry run, nothing written, " + report.count() + " would be raised");
                    return Main.OK;
                }
                uow.commit();
            }
            out.println();
            out.println(report.count() + " raised");
            return Main.OK;
        }
    }

    /** Write one credit against one invoice. */
    @Command(
            name = "credit",
            mixinStandardHelpOptions = true,
            description = "Write a credit of AMOUNT against INVOICE_NUMBER.")
    static final class Credit implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Parameters(index = "0", paramLabel = "INVOICE_NUMBER")
        private String invoiceNumber;

        @Parameters(index = "1", paramLabel = "AMOUNT")
        private String amount;

        @Option(
                names = "--reason",
                required = true,
                description = "Why. The month end report groups by this: ${COMPLETION-CANDIDATES}.")
        private Reason reason;

        @Option(names = "--note", defaultValue = "", description = "Needed when the reason is other.")
        private String note;

        @Option(names = "--on", converter = Days.class, description = "Date to raise it on. Defaults to today.")
        private LocalDate on;

        @Option(names = "--spots", defaultValue = "0", description = "How many spots it covers.")
        private int spots;

        @Override
        public Integer call() {
            Money gross = Money.parse(amount);
            CreditRow row;
            Standing before;
            Standing after;
            try (UnitOfWork uow = main.work()) {
                before = Crediting.standing(uow, invoiceNumber);
                row = Crediting.raiseCredit(uow, invoiceNumber, gross, reason, dayOr(on), note, spots);
                after = Crediting.standing(uow, invoiceNumber);
                uow.commit();
            }
            PrintWriter out = main.terminal().out();
            out.println(row.getNumber() + " against " + row.getInvoiceNumber() + " for "
                    + row.getReason().value());
            out.println("  gross      " + row.getGross());
            out.println("  commission " + row.getCommission());
            out.println("  net        " + row.getNet());
            out.println("  owing      " + before.owing() + " becomes " + after.owing());
            return Main.OK;
        }
    }

    /** What is still out, most owing first. */
    @Command(
            name = "owing",
            mixinStandardHelpOptions = true,
            description = "Every invoice with something still on it, most owing first.")
    static final class Owing implements Callable<Integer> {

        @ParentCommand
        private Main main;

        @Option(names = "--limit", defaultValue = "20", description = "How many to show.")
        private int limit;

        @Override
        public Integer call() {
            List<Standing> open;
            try (UnitOfWork uow = main.work()) {
                open = Crediting.outstanding(uow);
            }
            PrintWriter out = main.terminal().out();
            if (open.isEmpty()) {
                out.println("nothing outstanding");
                return Main.OK;
            }
            // A negative limit leaves that many off the end, as a slice would.
            int shown = limit >= 0 ? Math.min(limit, open.size()) : Math.max(open.size() + limit, 0);
            open.subList(0, shown)
                    .forEach(entry -> out.println(String.format(
                            Locale.ROOT, "%-12s %12s  of %s", entry.invoice(), entry.owing(), entry.billed())));
            Money total = Money.total(open.stream().map(Standing::owing).toList());
            out.println();
            out.println(open.size() + " invoices, " + total + " outstanding");
            return Main.OK;
        }
    }
}
