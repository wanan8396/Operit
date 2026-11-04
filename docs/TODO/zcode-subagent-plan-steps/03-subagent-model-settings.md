# Independent subagent model settings

Append SUBAGENT to FunctionType without changing existing ordinal positions. The functional model settings screen exposes the same configuration selector, model selector and connection test as other functions.

Each delegation snapshots the SUBAGENT configId/modelIndex mapping. Execute the tool-enabled CHAT pipeline with those explicit overrides, and derive context limits from the same selected configuration. Parent role, tool permissions and workspace are unchanged. Both agent profiles share this mapping.

Cloud tests cover enum identity, normalization of existing settings, independent model indices and deleted configuration references. Release packaging remains :app:packageRelease with application ID com.ai.assistance.operit. Runtime verification should select different parent/child models and confirm child requests reach the selected provider/model.

[DONE] Implementation and CI test selection. Cloud and device validation pending.
