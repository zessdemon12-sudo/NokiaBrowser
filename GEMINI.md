# Agent Rules for Nokia J2ME Browser Project

## Mandatory Rule: Project Activity & State Logging

You MUST maintain a comprehensive, up-to-date Markdown record of everything you do in [`PROJECT_LOG.md`](./PROJECT_LOG.md).

### Requirements for Agents:
1. **Always Update `PROJECT_LOG.md`**:
   - Whenever you implement a new feature, fix a bug, refactor code, update server routes, modify the build system, or verify behavior, you MUST update [`PROJECT_LOG.md`](./PROJECT_LOG.md) before concluding your turn.
2. **Machine-Readable & Agent-Analyzable Structure**:
   - Write in structured Markdown with semantic headers, markdown tables, code references, and structured metadata blocks (e.g. JSON/YAML blocks).
   - This ensures that other AI agents can parse, index, search, and recall past decisions, architectural patterns, and known issues without losing context.
3. **What to Record for Every Action**:
   - **Timestamp & Goal**: What the user requested or what problem was addressed.
   - **Root Cause & Technical Analysis**: Why the issue occurred or why a specific architecture was chosen.
   - **Files Modified / Created / Deleted**: Exact paths with summaries of changes.
   - **Architecture & Protocol Details**: Ports, endpoints, packet formats, codecs, parameters.
   - **Verification & Test Results**: Commands executed, test outputs, and validation status.
   - **Current Project State**: Snapshot of active features, known constraints, and next recommended steps.
4. **Preserve Historical Logs**:
   - Never delete previous log entries in `PROJECT_LOG.md`. Append new events chronologically to maintain a complete audit trail and continuous memory across agent sessions.

## Mandatory Rule: Open Source Licensing (MIT License on GitHub Uploads)

Every time everything is uploaded to GitHub, ensure the project includes the MIT License:
1. Maintain a valid `LICENSE` file containing the standard MIT License at the root of the repository.
2. Ensure repository metadata and documentation (`README.md`, `LICENSE`, etc.) explicitly declare the MIT License.
3. Every GitHub repository creation or push must include the MIT License.

