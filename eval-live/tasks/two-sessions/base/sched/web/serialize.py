def reminder_json(reminder):
    return {"id": reminder.id, "text": reminder.text, "due": reminder.due.isoformat(), "urgent": reminder.urgent}
