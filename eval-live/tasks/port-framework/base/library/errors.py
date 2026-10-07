class ValidationError(Exception):
    """A request body that is well-formed JSON but not a valid book; ``fields`` names what is wrong, field by field."""

    def __init__(self, fields):
        super().__init__("validation failed")
        self.fields = dict(fields)
