import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const requestBlobMock = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api/http-client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/http-client")>();
  return { ...actual, requestBlob: requestBlobMock };
});

import { downloadRequest } from "./api";

describe("downloadRequest", () => {
  const clicked: Array<{ href: string; download: string }> = [];

  beforeEach(() => {
    requestBlobMock.mockReset();
    clicked.length = 0;
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => "blob:portfolio-mis"),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn((url: string) => url),
    });
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (
      this: HTMLAnchorElement,
    ) {
      clicked.push({ href: this.href, download: this.download });
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("downloads the authenticated Blob and revokes its object URL", async () => {
    requestBlobMock.mockResolvedValue({ blob: new Blob(["csv"]), filename: "portfolio.csv" });

    await downloadRequest("report-1");

    expect(requestBlobMock).toHaveBeenCalledWith(
      "/api/v1/internal/reports/requests/report-1/download",
    );
    expect(clicked).toEqual([{ href: "blob:portfolio-mis", download: "portfolio.csv" }]);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:portfolio-mis");
  });
});
