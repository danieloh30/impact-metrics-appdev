import {defineConfig} from '@playwright/test';
export default defineConfig({testDir:'e2e',timeout:60000,use:{baseURL:process.env.METRICS_URL||'http://127.0.0.1:8097',viewport:{width:1440,height:1000},trace:'retain-on-failure',launchOptions:{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH}},outputDir:'../../../target/playwright',reporter:'list',workers:1});
