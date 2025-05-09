"""carry what actually aired on the spot row

Billing reads the spot, so what the as-run said about it belongs on the spot
and not in a table of its own. Four columns: whether it went out, when it
really went out, how long it really ran, and the automation's own word for what
happened to it.

Everything already on file predates the as-run import, so the columns come in
with the values that mean "nobody has posted an as-run against this yet".

Revision ID: 0002
Revises: 0001
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "0002"
down_revision = "0001"
branch_labels = None
depends_on = None


def upgrade() -> None:
    with op.batch_alter_table("air_spot") as batch:
        batch.add_column(
            sa.Column("aired", sa.Boolean(), nullable=False, server_default=sa.false())
        )
        batch.add_column(sa.Column("aired_at", sa.Integer(), nullable=True))
        batch.add_column(sa.Column("aired_length", sa.Integer(), nullable=True))
        batch.add_column(
            sa.Column(
                "aired_status",
                sa.String(length=32),
                nullable=False,
                server_default="unknown",
            )
        )


def downgrade() -> None:
    with op.batch_alter_table("air_spot") as batch:
        batch.drop_column("aired_status")
        batch.drop_column("aired_length")
        batch.drop_column("aired_at")
        batch.drop_column("aired")
