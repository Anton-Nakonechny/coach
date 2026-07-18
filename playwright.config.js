const { defineConfig } = require('@playwright/test');

module.exports = defineConfig({
    testDir: './e2e',
    use: {
        baseURL: 'http://localhost:9999',
    },
    webServer: {
        command: 'mvn spring-boot:run -q',
        url: 'http://localhost:9999/api/models',
        reuseExistingServer: true,
        timeout: 60_000,
    },
});
