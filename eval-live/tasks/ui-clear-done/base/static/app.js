"use strict";

const form = document.getElementById("new-todo-form");
const titleInput = document.getElementById("new-title");
const list = document.getElementById("todo-list");
const count = document.getElementById("todo-count");

let todos = [];

async function api(method, path, body) {
  const response = await fetch(path, {
    method,
    headers: { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${path} failed with ${response.status}`);
  }
  return response.json();
}

function render() {
  list.textContent = "";
  for (const todo of todos) {
    const item = document.createElement("li");
    item.className = todo.done ? "done" : "";
    const box = document.createElement("input");
    box.type = "checkbox";
    box.checked = todo.done;
    box.addEventListener("change", () => toggle(todo.id, box.checked));
    const label = document.createElement("span");
    label.textContent = todo.title;
    item.append(box, label);
    list.append(item);
  }
  const left = todos.filter((todo) => !todo.done).length;
  count.textContent = `${left} ${left === 1 ? "item" : "items"} left`;
}

async function load() {
  todos = await api("GET", "/api/todos");
  render();
}

async function toggle(id, done) {
  await api("PATCH", `/api/todos/${id}`, { done });
  await load();
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  const title = titleInput.value.trim();
  if (!title) return;
  await api("POST", "/api/todos", { title });
  titleInput.value = "";
  await load();
});

load();
