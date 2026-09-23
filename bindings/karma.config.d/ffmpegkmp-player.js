const ffmpegKmpPlayerAssets = [
    "kotlin/ffmpegkmp-worker.mjs",
    "kotlin/ffmpegkmp.mjs",
    "kotlin/ffmpegkmp.wasm",
    "kotlin/hardware-h264.mp4"
];

config.client.useIframe = false;
config.client.mocha = {
    ...(config.client.mocha || {}),
    timeout: 120000
};
config.browserNoActivityTimeout = 120000;

config.files.push(...ffmpegKmpPlayerAssets.map(pattern => ({
    pattern,
    included: false,
    served: true,
    watched: false
})));

function FFmpegKmpPlayerIsolationMiddlewareFactory() {
    return function (_request, response, next) {
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
        next();
    };
}

config.beforeMiddleware = [
    ...(config.beforeMiddleware || []),
    "ffmpegkmp-player-cross-origin-isolation"
];
config.plugins.push({
    "middleware:ffmpegkmp-player-cross-origin-isolation": [
        "factory",
        FFmpegKmpPlayerIsolationMiddlewareFactory
    ]
});

config.customHeaders = [
    ...(config.customHeaders || []),
    {
        match: ".*",
        name: "Cross-Origin-Opener-Policy",
        value: "same-origin"
    },
    {
        match: ".*",
        name: "Cross-Origin-Embedder-Policy",
        value: "require-corp"
    }
];

// The audio tests play through a real AudioContext, which Chrome otherwise holds until a gesture.
config.customLaunchers = {
    ...(config.customLaunchers || {}),
    ChromeHeadlessAutoplay: {
        base: "ChromeHeadless",
        flags: ["--autoplay-policy=no-user-gesture-required"]
    }
};
config.browsers = (config.browsers || []).map(browser =>
    browser === "ChromeHeadless" ? "ChromeHeadlessAutoplay" : browser
);
