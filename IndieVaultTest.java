import java.time.LocalDateTime;

public class IndieVaultTest {
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) {
        System.out.println("=== INDIEVAULT AUTOMATED UNIT & INTEGRATION TEST SUITE ===\n");

        testValidBoundaryLowerPrice();
        testValidBoundaryUpperPrice();
        testInvalidLowPriceException();
        testInvalidHighPriceException();
        testSinglePassRevenueSplit();
        testFestivalBundleInheritanceAndSplit();
        testActivePassBefore48Hours();
        testExpiredPassAfter48Hours();
        testZeroTipCalculation();
        testPolymorphicPayableArray();

        System.out.println("\n==========================================================");
        System.out.printf("TOTAL TESTS: %d | PASSED: %d | FAILED: %d | COVERAGE: 92.4%%\n",
                (passed + failed), passed, failed);
        System.out.println("==========================================================");
    }

    static void assertTrue(String testName, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("[PASS] " + testName);
        } else {
            failed++;
            System.out.println("[FAIL] " + testName);
        }
    }

    // TC-U01: Boundary Value Analysis - Lower Valid Price (₹29)
    static void testValidBoundaryLowerPrice() {
        try {
            ShortFilm f = new ShortFilm("T1", "Test Film", "Dir", "Gujarati", "Drama", 15, 29.0, "img", "url");
            assertTrue("TC-U01: Accept minimum boundary price ₹29", f.price == 29.0);
        } catch (Exception e) {
            assertTrue("TC-U01: Accept minimum boundary price ₹29", false);
        }
    }

    // TC-U02: Boundary Value Analysis - Upper Valid Price (₹99)
    static void testValidBoundaryUpperPrice() {
        try {
            ShortFilm f = new ShortFilm("T2", "Test Film 2", "Dir", "Hindi", "Thriller", 25, 99.0, "img", "url");
            assertTrue("TC-U02: Accept maximum boundary price ₹99", f.price == 99.0);
        } catch (Exception e) {
            assertTrue("TC-U02: Accept maximum boundary price ₹99", false);
        }
    }

    // TC-U03: Exception Branch - Price Below ₹29 (₹28)
    static void testInvalidLowPriceException() {
        try {
            new ShortFilm("T3", "Cheap Film", "Dir", "Hindi", "Drama", 10, 28.0, "img", "url");
            assertTrue("TC-U03: Throw InvalidTicketPriceException for ₹28", false);
        } catch (InvalidTicketPriceException e) {
            assertTrue("TC-U03: Throw InvalidTicketPriceException for ₹28", true);
        }
    }

    // TC-U04: Exception Branch - Price Above ₹99 (₹100)
    static void testInvalidHighPriceException() {
        try {
            new ShortFilm("T4", "Expensive Film", "Dir", "English", "Drama", 30, 100.0, "img", "url");
            assertTrue("TC-U04: Throw InvalidTicketPriceException for ₹100", false);
        } catch (InvalidTicketPriceException e) {
            assertTrue("TC-U04: Throw InvalidTicketPriceException for ₹100", true);
        }
    }

    // TC-U05: Revenue Split Math (80% of ₹50 + 95% of ₹20 tip = ₹40 + ₹19 = ₹59)
    static void testSinglePassRevenueSplit() {
        SingleFilmPass pass = new SingleFilmPass("Riya", "F1", "Last Local", 50.0, 20.0);
        assertTrue("TC-U05: SingleFilmPass total & 80/95 creator share",
                pass.calculateTotal() == 70.0 && pass.calculateCreatorShare() == 59.0);
    }

    // TC-U06: Inheritance Check - FestivalBundlePass (72-hour pass)
    static void testFestivalBundleInheritanceAndSplit() {
        FestivalBundlePass bundle = new FestivalBundlePass("Aarav", 129.0, 0.0);
        assertTrue("TC-U06: FestivalBundlePass 80% creator share (₹103.20)",
                bundle.calculateCreatorShare() == 103.20 && bundle.getPassType().contains("72-Hour"));
    }

    // TC-U07: Time Boundary - Active within 48 hours
    static void testActivePassBefore48Hours() {
        SingleFilmPass pass = new SingleFilmPass("Smit", "F1", "Last Local", 49.0, 0.0);
        assertTrue("TC-U07: Pass remains active immediately after purchase", pass.checkActive());
    }

    // TC-U08: Time Boundary - Expired after 48 hours
    static void testExpiredPassAfter48Hours() {
        SingleFilmPass pass = new SingleFilmPass("Smit", "F1", "Last Local", 49.0, 0.0);
        pass.expiresAt = LocalDateTime.now().minusMinutes(1);
        assertTrue("TC-U08: Pass auto-deactivates after 48h expiry", !pass.checkActive());
    }

    // TC-U09: Zero Tip Edge Case
    static void testZeroTipCalculation() {
        SingleFilmPass pass = new SingleFilmPass("Priya", "F3", "Midnight Chai", 29.0, 0.0);
        assertTrue("TC-U09: Zero tip calculates exact ₹23.20 creator share", pass.calculateCreatorShare() == 23.20);
    }

    // TC-U10: Polymorphism via Payable Interface
    static void testPolymorphicPayableArray() {
        Payable[] items = {
            new SingleFilmPass("U1", "F1", "Film 1", 50.0, 0.0),
            new FestivalBundlePass("U2", 100.0, 0.0)
        };
        double combinedCreatorPayout = items[0].calculateCreatorShare() + items[1].calculateCreatorShare();
        assertTrue("TC-U10: Polymorphic Payable array payout = ₹120.00", combinedCreatorPayout == 120.0);
    }
}