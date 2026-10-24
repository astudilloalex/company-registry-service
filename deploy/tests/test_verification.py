"""Reject effective deployment drift without requiring containers or a VPS."""

import copy
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
from types import SimpleNamespace


spec = importlib.util.spec_from_file_location("verify", Path(__file__).resolve().parents[1] / "vps/verify.py")
verify = importlib.util.module_from_spec(spec)
spec.loader.exec_module(verify)


def container() -> dict:
    return {
        "State": {"Running": True},
        "NetworkSettings": {"Networks": {name: {} for name in verify.NETWORKS},
                            "Ports": {"8083/tcp": [{"HostIp": "127.0.0.1", "HostPort": "8083"}]}},
        "Config": {"User": "1001:1001", "Env": ["COMPANY_OUTBOX_MODE=disabled", "QUARKUS_HTTP_PORT=8083"]},
        "HostConfig": {"ReadonlyRootfs": True, "SecurityOpt": ["no-new-privileges"], "CapAdd": []},
    }


class InspectionTest(unittest.TestCase):
    """Check concrete exposure, topology, mode and privilege violations."""

    def test_agreed_private_disabled_deployment(self):
        original = container()
        verify.validate_inspection(original, "disabled")
        self.assertEqual(original, container())

    def test_unapproved_effective_settings_are_rejected(self):
        mutations = (
            lambda c: c["State"].update(Running=False),
            lambda c: c["NetworkSettings"]["Networks"].pop("messaging"),
            lambda c: c["NetworkSettings"]["Networks"].update(unapproved={}),
            lambda c: c["NetworkSettings"]["Ports"]["8083/tcp"][0].update(HostIp="0.0.0.0"),
            lambda c: c["NetworkSettings"]["Ports"].update({"8080/tcp": None}),
            lambda c: c["Config"].update(User="0"),
            lambda c: c["Config"].update(Env=["COMPANY_OUTBOX_MODE=published", "QUARKUS_HTTP_PORT=8083"]),
            lambda c: c["HostConfig"].update(ReadonlyRootfs=False),
            lambda c: c["HostConfig"].update(SecurityOpt=[]),
            lambda c: c["HostConfig"].update(CapAdd=["SYS_ADMIN"]),
        )
        for index, mutate in enumerate(mutations):
            with self.subTest(case=index):
                changed = copy.deepcopy(container())
                mutate(changed)
                with self.assertRaises(ValueError):
                    verify.validate_inspection(changed, "disabled")

    def test_privilege_finalization_uses_migration_password_only_through_environment(self):
        inspected = container()
        inspected["Config"]["Env"] += ["DB_USERNAME=company_registry_runtime",
            "FLYWAY_DB_USERNAME=company_registry_migration", "FLYWAY_DB_PASSWORD=test-migration-secret",
            "POSTGRES_CONTAINER=postgresql"]
        with patch.object(verify.subprocess, "run", return_value=SimpleNamespace(stdout="GRANT\nREVOKE\nt\n")) as run:
            verify.finalize_database(inspected)
        arguments = run.call_args.args[0]
        self.assertNotIn("test-migration-secret", " ".join(arguments))
        self.assertIn("--username=company_registry_migration", arguments)
        self.assertIn("--dbname=company_registry_service", arguments)
        self.assertEqual(run.call_args.kwargs["env"]["PGPASSWORD"], "test-migration-secret")
        sql = run.call_args.kwargs["input"]
        self.assertIn("REVOKE ALL ON public.flyway_schema_history", sql)
        self.assertNotIn("DROP ", sql)

    def test_replay_smoke_rejects_changed_accepted_result(self):
        with patch.object(verify, "request", side_effect=[{"data": {"version": 0}}, {"data": {"version": 1}}]):
            with self.assertRaises(ValueError):
                verify.accept_and_replay("/v1/companies", {}, {}, "test-smoke-key")


if __name__ == "__main__":
    unittest.main()
