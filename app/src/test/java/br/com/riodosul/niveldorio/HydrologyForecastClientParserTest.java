package br.com.riodosul.niveldorio;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HydrologyForecastClientParserTest {
    private static String fixture() throws Exception {
        InputStream input = HydrologyForecastClientParserTest.class.getClassLoader()
                .getResourceAsStream("forecast-v2-live.json");
        assertNotNull(input);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        input.close();
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    @Test public void parsesLiveV2LatestHistoryAccuracyAndNullWeather() throws Exception {
        JSONObject root = new JSONObject(fixture());
        long now = DataRepository.parseTimestamp("2026-11-13T00:47:00.000Z");
        HydrologyForecastClient.Response response = HydrologyForecastClient.parseResponse(root, now, false);
        assertTrue(response.contractV2);
        assertEquals("deterministic-v2", response.modelVersion);
        assertEquals(3, response.latest.size());
        assertEquals(9, response.history.size());
        assertEquals(9, response.accuracy.size());
        Forecast dom = response.latest.get(Bridge.DOM_TITO);
        assertNotNull(dom);
        assertTrue(dom.available);
        assertEquals(3, dom.horizons.size());
        assertEquals(1, dom.horizon(1).hours);
        assertTrue(Double.isNaN(dom.rainNextHourMm));
        assertTrue(Double.isNaN(dom.rainNext2hMm));
        assertTrue(Double.isNaN(dom.rainNext3hMm));
        assertTrue(dom.notes.size() >= 10);
        assertEquals(1, response.groupsFor(Bridge.DOM_TITO, 12).size());
        assertEquals(3, response.groupsFor(Bridge.DOM_TITO, 12).get(0).rows.length);
    }

    @Test public void rejectsLegacyPayloadWithoutComputingFallback() throws Exception {
        JSONObject legacy = new JSONObject();
        legacy.put("model_version", "legacy-v1");
        legacy.put("latest", new org.json.JSONArray().put(new JSONObject()
                .put("bridge", "dom-tito").put("available", true)));
        HydrologyForecastClient.Response response = HydrologyForecastClient.parseResponse(legacy,
                System.currentTimeMillis(), false);
        assertFalse(response.contractV2);
        assertTrue(response.latest.isEmpty());
    }

    @Test public void neverShowsOldOrFutureForecastAsCurrent() throws Exception {
        JSONObject root = new JSONObject(fixture());
        long after = DataRepository.parseTimestamp("2026-11-13T02:00:00Z");
        long before = DataRepository.parseTimestamp("2026-11-13T00:00:00Z");
        for (long clock : new long[] { after, before }) {
            HydrologyForecastClient.Response response = HydrologyForecastClient.parseResponse(root, clock, false);
            for (Forecast forecast : response.latest.values()) {
                assertFalse(forecast.available);
                assertTrue(forecast.stale);
            }
        }
    }

    @Test public void appliesOfficialCotaBoundaries() {
        Cotas cotas = new Cotas();
        assertEquals(0, cotas.faixa(4.499));
        assertEquals(1, cotas.faixa(4.5));
        assertEquals(1, cotas.faixa(5.499));
        assertEquals(2, cotas.faixa(5.5));
        assertEquals(2, cotas.faixa(6.5));
        assertEquals(3, cotas.faixa(6.501));
    }
}
