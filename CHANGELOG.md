Hotfix addressing broken developer dependency resolution when fetching artifacts from Modrinth. Now IDEs can properly
resolve and attach sources without manual workarounds.

---

### ⚡ Performance & Stability

* **Modrinth Sources Resolution (`complexity-analyzer`)**: Aligned the archive name with the official project slug,
  fixing failed `-sources.jar` downloads and unresolved dependencies when pulled via Modrinth Maven.