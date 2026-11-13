package br.com.riodosul.niveldorio;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import br.com.riodosul.niveldorio.HydrologyForecastClient.HistoryGroup;
import br.com.riodosul.niveldorio.HydrologyForecastClient.HistoryRow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ForecastHistorySelectionTest {
    @Test
    public void filtersBeforePagingThirtyGroupsAndKeepsAllThreeHorizons() {
        List<HistoryGroup> input = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            input.add(i < 12 ? group(i, "pendente", "pendente", "pendente")
                    : group(i, "dentro", "quase", "fora"));
        }

        List<HistoryGroup> checked = ForecastHistorySelection.filter(input, true);
        List<HistoryGroup> firstPage = ForecastHistorySelection.visible(
                checked, ForecastHistorySelection.PAGE_SIZE);

        assertEquals(18, checked.size());
        assertEquals(12, firstPage.size());
        for (int i = 0; i < firstPage.size(); i++) {
            HistoryGroup selected = firstPage.get(i);
            assertEquals(12 + i, selected.baseTime);
            assertTrue(ForecastHistorySelection.isFullyChecked(selected));
            assertEquals("dentro", selected.rows[0].outcome);
            assertEquals("quase", selected.rows[1].outcome);
            assertEquals("fora", selected.rows[2].outcome);
        }
    }

    @Test
    public void allModePreservesPartiallyCheckedGroupsOrderAndDoesNotMutateInput() {
        HistoryGroup partial = group(10, "dentro", "pendente", "fora");
        HistoryGroup checked = group(20, "dentro", "quase", "fora");
        HistoryGroup pending = group(30, "pendente", "pendente", "pendente");
        List<HistoryGroup> input = Arrays.asList(partial, checked, pending);
        String originalOutcome = partial.rows[1].outcome;

        List<HistoryGroup> all = ForecastHistorySelection.filter(input, false);
        List<HistoryGroup> selected = ForecastHistorySelection.filter(input, true);

        assertNotSame(input, all);
        assertEquals(input, all);
        assertSame(partial, all.get(0));
        assertSame(checked, all.get(1));
        assertSame(pending, all.get(2));
        assertEquals(Arrays.asList(checked), selected);
        assertEquals(originalOutcome, partial.rows[1].outcome);

        all.clear();
        assertEquals(3, input.size());
        assertEquals(originalOutcome, input.get(0).rows[1].outcome);
    }

    @Test
    public void checkedFilterExcludesNoMeasurementIncompleteAndInvalidGroups() {
        HistoryGroup noMeasurement = group(1, "dentro", "sem_medicao", "fora");
        HistoryGroup incomplete = group(2, "dentro", "quase", "fora");
        incomplete.rows[2] = null;
        HistoryGroup invalid = group(3, "dentro", "pendente", "fora");
        HistoryGroup missingOutcome = group(4, "dentro", null, "fora");
        HistoryGroup valid = group(5, "dentro", "quase", "fora");
        List<HistoryGroup> input = Arrays.asList(
                noMeasurement, incomplete, invalid, missingOutcome, valid, null);

        List<HistoryGroup> result = ForecastHistorySelection.filter(input, true);

        assertEquals(Arrays.asList(valid), result);
        assertFalse(ForecastHistorySelection.isFullyChecked(noMeasurement));
        assertFalse(ForecastHistorySelection.isFullyChecked(incomplete));
        assertFalse(ForecastHistorySelection.isFullyChecked(null));
    }

    @Test
    public void checkedOutcomeIsCaseInsensitiveAndIndependentOfLevelOrError() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            HistoryGroup uppercase = group(7, "DENTRO", "QuAsE", "FORA");
            for (HistoryRow row : uppercase.rows) {
                row.currentLevel = Double.NaN;
                row.predictedLevel = Double.NaN;
                row.errorCm = Double.NaN;
            }

            assertTrue(ForecastHistorySelection.isFullyChecked(uppercase));
            assertEquals(Arrays.asList(uppercase),
                    ForecastHistorySelection.filter(Arrays.asList(uppercase), true));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    public void nullAndNonPositiveLimitsReturnEmptyAndHighLimitReturnsCopy() {
        HistoryGroup first = group(1, "dentro", "quase", "fora");
        HistoryGroup second = group(2, "pendente", "pendente", "pendente");
        List<HistoryGroup> input = Arrays.asList(first, second);

        assertTrue(ForecastHistorySelection.filter(null, false).isEmpty());
        assertTrue(ForecastHistorySelection.filter(null, true).isEmpty());
        assertTrue(ForecastHistorySelection.visible(null, 12).isEmpty());
        assertTrue(ForecastHistorySelection.visible(input, 0).isEmpty());
        assertTrue(ForecastHistorySelection.visible(input, -1).isEmpty());

        List<HistoryGroup> allVisible = ForecastHistorySelection.visible(input, 100);
        assertEquals(input, allVisible);
        assertNotSame(input, allVisible);
        allVisible.clear();
        assertEquals(2, input.size());
    }

    @Test
    public void expandingPagesFromTwelveToTwentyFourToThirtyHasNoLossOrDuplicates() {
        List<HistoryGroup> input = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            input.add(group(i, "dentro", "quase", "fora"));
        }

        List<HistoryGroup> page12 = ForecastHistorySelection.visible(input, 12);
        List<HistoryGroup> page24 = ForecastHistorySelection.visible(input, 24);
        List<HistoryGroup> page30 = ForecastHistorySelection.visible(input, 30);

        assertEquals(12, page12.size());
        assertEquals(24, page24.size());
        assertEquals(30, page30.size());
        for (int i = 0; i < 12; i++) assertSame(page12.get(i), page24.get(i));
        for (int i = 0; i < 24; i++) assertSame(page24.get(i), page30.get(i));
        Set<Long> baseTimes = new HashSet<>();
        for (HistoryGroup group : page30) baseTimes.add(group.baseTime);
        assertEquals(30, baseTimes.size());
    }

    @Test
    public void visiblePreservesInputOrderAndReturnsOnlyRequestedPrefix() {
        HistoryGroup first = group(30, "dentro", "quase", "fora");
        HistoryGroup second = group(10, "pendente", "pendente", "pendente");
        HistoryGroup third = group(20, "dentro", "quase", "fora");
        List<HistoryGroup> input = Arrays.asList(first, second, third);

        List<HistoryGroup> result = ForecastHistorySelection.visible(input, 2);

        assertEquals(Arrays.asList(first, second), result);
        assertEquals(30, result.get(0).baseTime);
        assertEquals(10, result.get(1).baseTime);
        assertEquals(3, input.size());
    }

    private static HistoryGroup group(long baseTime, String first, String second, String third) {
        HistoryGroup group = new HistoryGroup();
        group.baseTime = baseTime;
        group.currentLevel = 123.45;
        String[] outcomes = {first, second, third};
        for (int i = 0; i < outcomes.length; i++) {
            HistoryRow row = new HistoryRow();
            row.horizonH = i + 1;
            row.outcome = outcomes[i];
            row.currentLevel = 123.45;
            row.predictedLevel = 123.50 + i;
            row.errorCm = i;
            group.rows[i] = row;
        }
        return group;
    }
}
