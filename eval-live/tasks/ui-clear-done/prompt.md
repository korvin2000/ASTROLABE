Add a "Clear completed" button to the todo app (`server.py` and the page in `static/`; there is no build step).

- `static/index.html` gets a `<button id="clear-completed">` labelled "Clear completed". Clicking it removes every
  completed todo and keeps the others; the list and the "N items left" counter show the new state at once, without
  reloading the page.
- The button is hidden while there is no completed todo and appears as soon as there is one.
- The server gets a new operation for it: `DELETE /api/todos?done=true` removes all completed todos and answers 200
  with `{"removed": <how many>}`. A `DELETE /api/todos` without `done=true` is refused with 400 and removes nothing.
  Todo ids are still never reused. `static/app.js` calls this operation from the button.

Keep the existing tests passing and add a test for the new operation.
