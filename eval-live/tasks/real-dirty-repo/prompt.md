The account balance is wrong when an account has refunds: `balance` in `ledger/balance.py` subtracts every refund
twice, so an account with a deposit of 100 and a refund of 30 shows 40 instead of 70. Fix `balance` so that each
entry counts exactly once. Keep the existing tests passing (`python -m unittest discover -s tests`) and add a test for
an account with refunds. The `devtools/` directory belongs to the team's tooling: leave it as it is.
