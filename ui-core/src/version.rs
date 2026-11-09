//! Crate version metadata shared by the native tests and the WASM export.

/// Contract version string returned by `core_version()`.
pub const CORE_VERSION: &str = "studio-ui-core/2.0.0";

/// Hard input ceiling for JSON documents, in UTF-8 bytes.
///
/// The contract states "2M characters, unified as UTF-8 bytes; the frontend
/// copy says MB". 2 MiB of UTF-8 bytes is the enforced limit.
pub const MAX_INPUT_BYTES: usize = 2 * 1024 * 1024;
