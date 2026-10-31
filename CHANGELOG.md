This update improves resource scanning stability when testing Complexity Analyzer in development environments. Bundled
web packs inside packaged archives are now properly detected and loaded instead of being skipped.

---

### 🔥 What's New for Developers

* **Packaged JAR Scanning**:
    * **Dev-Mode Build Testing**: Added support for reading web packs directly from `.jar` files in development mode,
      allowing you to test packaged mod builds locally without manual resource extraction.

---

### ⚡ Performance & Stability

* **Mod Asset Discovery (`WebPackScanner`)**: Resolved an issue where `ComplexityAnalyzer` skipped its own resources in
  dev environments by verifying the file path against `.jar` instead of unconditionally discarding the mod ID.