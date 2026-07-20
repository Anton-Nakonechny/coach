const { defineConfig } = require('@playwright/test');

const port = process.env.TEST_PORT || 9999;

module.exports = defineConfig({
    testDir: './e2e',
    use: {
        baseURL: `http://localhost:${port}`,
    },
    webServer: {
        // Build the reactor once so coach-core is on the classpath, then run ONLY
        // coach-web. `spring-boot:run` is a direct CLI goal, so with `-am` it also
        // executes on coach-parent/coach-core (no main class) and dies before the
        // web app boots — hence the split: `-am install`, then run without `-am`.
        command: `mvn -pl coach-web -am -q -ntp -DskipTests install `
            + `&& SERVER_PORT=${port} mvn -pl coach-web -q -ntp spring-boot:run`,
        url: `http://localhost:${port}/api/models`,
        reuseExistingServer: true,
        timeout: 120_000,
    },
});
