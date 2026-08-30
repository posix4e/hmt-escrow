import XCTest

/// Screenshot-backed smoke test: drives the whole claim → label → submit →
/// paid flow in `--demo` mode on the simulator and attaches a screenshot
/// at each stage (exported by CI as artifacts).
final class LabelerUITests: XCTestCase {
    private func shoot(_ app: XCUIApplication, _ name: String) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }

    func testClaimLabelSubmitPaid() {
        let app = XCUIApplication()
        app.launchArguments = ["--demo"]
        app.launch()

        XCTAssertTrue(app.buttons["claimButton"].waitForExistence(timeout: 10))
        shoot(app, "ios-native-1-jobs")

        app.buttons["claimButton"].tap()
        XCTAssertTrue(app.buttons["choice-frame-0-cat"].waitForExistence(timeout: 10))

        app.buttons["choice-frame-0-cat"].tap()
        shoot(app, "ios-native-2-labeling")
        app.buttons["choice-frame-1-dog"].tap()
        app.buttons["choice-frame-2-cat"].tap()

        let submit = app.buttons["submitButton"]
        XCTAssertTrue(submit.waitForExistence(timeout: 5))
        submit.tap()

        XCTAssertTrue(app.staticTexts["labels accepted ✓"].waitForExistence(timeout: 10))
        let paid = app.staticTexts["earningSats"]
        if paid.waitForExistence(timeout: 10) {
            XCTAssertTrue(paid.label.contains("3000 sats"), "unexpected amount: \(paid.label)")
        } else {
            // dump the tree into the failure message so the job log carries it
            XCTFail("earnings row missing — hierarchy:\n\(app.debugDescription)")
        }
        shoot(app, "ios-native-3-paid")
    }
}
