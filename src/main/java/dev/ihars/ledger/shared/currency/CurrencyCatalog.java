package dev.ihars.ledger.shared.currency;

import dev.ihars.ledger.shared.money.CurrencyUnit;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Supported currencies and their minor units, read from the {@code currencies} table. */
@Component
public class CurrencyCatalog {

    private static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    private final JdbcClient jdbc;

    public CurrencyCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The currency if it is supported and active. */
    public Optional<CurrencyUnit> findActive(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT code, minor_units FROM currencies WHERE code = :code AND active")
                .param("code", code)
                .query((rs, n) -> new CurrencyUnit(rs.getString("code"), rs.getInt("minor_units")))
                .optional();
    }
}
