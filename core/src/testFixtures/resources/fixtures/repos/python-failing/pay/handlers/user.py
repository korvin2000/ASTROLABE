"""The `/user` handler."""


def handle_user(req):
    """Answers a user lookup.

    Takes the request only. `pay.router.dispatch` calls it that way; adding a
    context parameter here without changing the caller breaks the caller's
    test, which is the cross-file defect surface P1.12.2 edits.
    """
    user_id = req.get("user_id")
    if not user_id:
        return {"status": 400, "body": "user_id is required"}
    return {"status": 200, "body": "user " + str(user_id)}
