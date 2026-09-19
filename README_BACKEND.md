# BorsaTakipV5 Railway Backend

Production-backend transport for BIST data. It preserves the Android app's fail-closed semantics: delayed or out-of-session upstream data is not relabeled as real-time. VIOP endpoints intentionally return NOT_CONFIGURED until a licensed/real VIOP upstream is connected.

Required Railway variable: `BORSA_API_KEY`.
