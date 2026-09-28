-- ticketing_source, run by ticketing_owner. Simulated ticketing system of record (DR-06).

CREATE FUNCTION public.set_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END $$;

CREATE TABLE public.sale_point (
  sale_point_id TEXT        PRIMARY KEY CHECK (sale_point_id ~ '^(KIOSK|ONBOARD|APP)-[0-9A-Z]{1,16}$'),
  name          TEXT        NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
  kind          TEXT        NOT NULL CHECK (kind IN ('KIOSK', 'ONBOARD', 'APP')),
  stop_id       TEXT        NULL,
  route_id      TEXT        NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (kind <> 'KIOSK'   OR stop_id  IS NOT NULL),
  CHECK (kind <> 'ONBOARD' OR route_id IS NOT NULL)
);

CREATE TABLE public.ticket_transaction (
  transaction_id UUID          PRIMARY KEY,
  sale_point_id  TEXT          NOT NULL REFERENCES public.sale_point (sale_point_id),
  route_id       TEXT          NULL,
  stop_id        TEXT          NULL,
  ticket_type    TEXT          NOT NULL CHECK (ticket_type IN ('SINGLE', 'DAY', 'MONTH')),
  txn_type       TEXT          NOT NULL CHECK (txn_type IN ('SALE', 'REFUND')),
  amount         NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
  currency       CHAR(3)       NOT NULL DEFAULT 'USD' CHECK (currency = 'USD'),
  refund_of      UUID          NULL REFERENCES public.ticket_transaction (transaction_id),
  customer_ref   TEXT          NULL,  -- simulated PII, dropped by the ETL (DR-60)
  status         TEXT          NOT NULL DEFAULT 'COMPLETED' CHECK (status IN ('COMPLETED', 'VOIDED')),
  created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
  CHECK ((txn_type = 'REFUND') = (refund_of IS NOT NULL)),
  CHECK (refund_of IS DISTINCT FROM transaction_id)
);

CREATE INDEX ticket_transaction_created_at_idx ON public.ticket_transaction (created_at);
CREATE INDEX ticket_transaction_sale_point_idx ON public.ticket_transaction (sale_point_id, created_at);
CREATE INDEX ticket_transaction_refund_of_idx  ON public.ticket_transaction (refund_of) WHERE refund_of IS NOT NULL;

CREATE TRIGGER sale_point_updated_at BEFORE UPDATE ON public.sale_point
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();
CREATE TRIGGER ticket_transaction_updated_at BEFORE UPDATE ON public.ticket_transaction
  FOR EACH ROW EXECUTE FUNCTION public.set_updated_at();

-- Debezium heartbeat target (DR-64): keeps the replication slot moving when ticketing is idle.
CREATE TABLE public.debezium_heartbeat (
  id SMALLINT    PRIMARY KEY CHECK (id = 1),
  ts TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO public.debezium_heartbeat (id) VALUES (1);

-- Full row images so that UPDATE and DELETE events carry created_at, from which the
-- warehouse business key (sale_date, transaction_id) is derived.
ALTER TABLE public.sale_point         REPLICA IDENTITY FULL;
ALTER TABLE public.ticket_transaction REPLICA IDENTITY FULL;

-- Created here rather than by Debezium (publication.autocreate.mode=disabled), so the
-- connector user does not need to own the tables.
CREATE PUBLICATION pti_ticketing
  FOR TABLE public.sale_point, public.ticket_transaction, public.debezium_heartbeat;
