const { defineConfig } = require('@playwright/test');

const port = process.env.TEST_PORT || 9999;

module.exports = defineConfig({
    testDir: './e2e',
    use: {
        baseURL: `http://localhost:${port}`,
    },
    webServer: {
        command: `SERVER_PORT=${port} mvn -pl coach-web -am spring-boot:run -q`,
        url: `http://localhost:${port}/api/models`,
        reuseExistingServer: true,
        timeout: 60_000,
    },
});
