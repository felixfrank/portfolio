package name.abuchen.portfolio.snapshot.vorabpauschale;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;

import java.math.BigDecimal;

import org.junit.Test;

import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;

@SuppressWarnings("nls")
public class TeilfreistellungFactorTest
{
    @Test
    public void testDefaultIsOneWhenUnset()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        assertThat(TeilfreistellungFactor.of(client, security), comparesEqualTo(BigDecimal.ONE));
    }

    @Test
    public void testEquityEtfThirtyPercentExemptionGivesPointSeven()
    {
        Client client = new Client();
        Security security = new SecurityBuilder("EUR").addTo(client);
        var type = client.getSettings().getAttributeTypes()
                        .filter(t -> "teilfreistellung".equals(t.getId())).findFirst().orElseThrow();
        security.getAttributes().put(type, Double.valueOf(0.30));

        assertThat(TeilfreistellungFactor.of(client, security), comparesEqualTo(new BigDecimal("0.70")));
    }
}
