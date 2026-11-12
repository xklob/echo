# Branching surveys

Import a UTF-8 JSON file (at most 64 KiB) in Optional tools, then choose FILL SURVEY. The schema is adapted from copa-sh/echo. No source author's personal questionnaire is bundled.

```json
{
  "title": "Example",
  "start": "note",
  "nodes": {
    "note": {"type": "text", "question": "Note", "next": "done"},
    "done": {"type": "end", "question": "Save your answers."}
  }
}
```

Types: `text`, `choice`, `scale`, `end`. Choice nodes have `options: [{"label":"...", "next":"node-id"}]`. Scale nodes use `min`, `max` and `next`. Null/missing `next` ends the survey. Imports validate branch destinations and the start node. There may be at most 100 nodes, 20 options per choice and 21 scale values. Answers stop at 100 steps and 4096 characters each, so a cyclic imported graph cannot grow without limit.

BACK returns to the previous answered question. Completed answers save as a timestamped JSON trace carrying question text, answers and save time. Notes and surveys stay local unless explicitly shared or optional server sync is enabled.
