import { describe, expect, it } from "vitest";
import cases from "../../shared/golden-cases.json";
import owners from "../../shared/PROCUREMENTOWNERS?raw";
import { amountOf, evaluateMerge, resolveOwners, runChecks, type Facts } from "./engine";

describe("Java와 같은 골든 케이스", () => {
  for (const c of cases) {
    it(c.name, () => {
      const f = c.facts as unknown as Facts;
      expect(amountOf(f)).toBe(c.expect.amount);
      const checks = runChecks(f);
      const statuses = Object.fromEntries(checks.map((x) => [x.id, x.status]));
      expect(statuses).toMatchObject(c.expect.statuses);
      expect(resolveOwners(owners, f).map((r) => r.role)).toEqual(c.expect.reviewers);
      expect(checks.some((x) => x.status === "FAIL")).toBe(c.expect.blocked);
    });
  }
});

describe("머지 판단", () => {
  const need = [{ role: "TEAM_LEAD", reasons: ["always"] }];
  it("이전 리비전 승인은 무효다", () => {
    const d = evaluateMerge([], need, "kim", "h2", [{ reviewerId: "lee", role: "TEAM_LEAD", decision: "APPROVED", revisionHash: "h1" }]);
    expect(d.allowed).toBe(false);
  });
  it("요청자 본인 승인은 세지 않는다", () => {
    const d = evaluateMerge([], need, "kim", "h2", [{ reviewerId: "kim", role: "TEAM_LEAD", decision: "APPROVED", revisionHash: "h2" }]);
    expect(d.allowed).toBe(false);
  });
});
