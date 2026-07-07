package name.abuchen.portfolio.model;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.time.Instant;

import org.junit.Test;

import name.abuchen.portfolio.money.CurrencyUnit;
import name.abuchen.portfolio.money.Money;

@SuppressWarnings("nls")
public class VorabpauschalePersistenceTest
{
    private Client clientWithEntry()
    {
        Client client = new Client();
        Security security = new Security();
        security.setName("Test ETF");
        security.setCurrencyCode(CurrencyUnit.EUR);
        client.addSecurity(security);

        VorabpauschaleEntry entry = new VorabpauschaleEntry(security, 2024, new BigDecimal("2.53"),
                        new BigDecimal("0.70"), Money.of("EUR", 10_000_00), Money.of("EUR", 0),
                        Money.of("EUR", 177_10), Money.of("EUR", 177_10), Money.of("EUR", 123_97),
                        Instant.parse("2025-01-02T00:00:00Z"));
        client.addVorabpauschaleEntry(entry);
        return client;
    }

    private void assertEntry(Client client)
    {
        assertThat(client.getVorabpauschaleEntries().size(), is(1));
        VorabpauschaleEntry e = client.getVorabpauschaleEntries().get(0);
        assertThat(e.getSecurity().getName(), is("Test ETF"));
        assertThat(e.getYear(), is(2024));
        assertThat(e.getBasiszins(), is(new BigDecimal("2.53")));
        assertThat(e.getTeilfreistellungFactor(), is(new BigDecimal("0.70")));
        assertThat(e.getYearStartValue(), is(Money.of("EUR", 10_000_00)));
        assertThat(e.getCappedBasisertrag(), is(Money.of("EUR", 177_10)));
        assertThat(e.getVorabpauschale(), is(Money.of("EUR", 177_10)));
        assertThat(e.getTaxable(), is(Money.of("EUR", 123_97)));
        assertThat(e.getFinalizedAt(), is(Instant.parse("2025-01-02T00:00:00Z")));
    }

    @Test
    public void testProtobufRoundtrip() throws IOException
    {
        Client client = clientWithEntry();

        ProtobufWriter writer = new ProtobufWriter();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.save(client, out);
        out.close();

        Client reloaded = writer.load(new ByteArrayInputStream(out.toByteArray()));
        assertEntry(reloaded);
    }

    @Test
    public void testXmlRoundtrip() throws IOException
    {
        Client client = clientWithEntry();

        String xml = ClientTestUtilities.toString(client);
        Client reloaded = ClientFactory.load(new StringReader(xml));
        assertEntry(reloaded);
    }

    @Test
    public void testOldFileWithoutLedgerLoadsEmpty() throws IOException
    {
        Client client = new Client();
        Security security = new Security();
        security.setName("Plain");
        security.setCurrencyCode(CurrencyUnit.EUR);
        client.addSecurity(security);

        String xml = ClientTestUtilities.toString(client);
        Client reloaded = ClientFactory.load(new StringReader(xml));

        assertThat(reloaded.getVorabpauschaleEntries().isEmpty(), is(true));
    }
}
