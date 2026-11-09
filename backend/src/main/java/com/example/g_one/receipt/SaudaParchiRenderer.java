package com.example.g_one.receipt;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a bilingual Hindi/English HTML "Sauda Parchi" (सौदा पर्ची) for a negotiated deal.
 */
public final class SaudaParchiRenderer {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMMM uuuu, h:mm a").withZone(IST);

    private SaudaParchiRenderer() {}

    public static String renderHtml(DealReceipt receipt) {
        String formattedDate = receipt.atMs() > 0
                ? DATE_FMT.format(Instant.ofEpochMilli(receipt.atMs()))
                : "Recent Call";

        String commodity = escape(receipt.commodity() != null ? receipt.commodity() : "-");
        String variety = escape(receipt.variety() != null ? receipt.variety() : "-");
        String quantity = escape(receipt.quantity() != null ? receipt.quantity() : "-");
        String price = receipt.price() != null ? "₹ " + receipt.price().toPlainString() : "-";
        String unit = escape(receipt.unit() != null ? receipt.unit() : "quintal");
        String market = escape(receipt.market() != null ? receipt.market() : "Direct / Farm-gate");
        String pickup = escape(receipt.pickup() != null ? receipt.pickup() : "Buyer pickup");
        String state = escape(receipt.state() != null ? receipt.state().toUpperCase() : "DRAFT");
        String farmerYes = receipt.farmerYes() ? "हाँ / Yes" : "प्रतीक्षारत / Pending";
        String buyerYes = receipt.buyerYes() ? "हाँ / Yes" : "प्रतीक्षारत / Pending";

        return """
                <!DOCTYPE html>
                <html lang="hi">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1.0">
                  <title>सौदा पर्ची / Sauda Parchi - %s</title>
                  <style>
                    body {
                      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Noto Sans Devanagari", sans-serif;
                      background-color: #f6f8fa;
                      color: #1f2328;
                      margin: 0;
                      padding: 24px;
                      display: flex;
                      justify-content: center;
                    }
                    .parchi-card {
                      background: #ffffff;
                      border: 1px solid #d0d7de;
                      border-radius: 12px;
                      max-width: 650px;
                      width: 100%%;
                      box-shadow: 0 4px 16px rgba(0,0,0,0.06);
                      overflow: hidden;
                    }
                    .header {
                      background: linear-gradient(135deg, #1b4d3e, #2d7a58);
                      color: #ffffff;
                      padding: 24px;
                      text-align: center;
                    }
                    .header h1 {
                      margin: 0 0 6px 0;
                      font-size: 26px;
                      letter-spacing: 0.5px;
                    }
                    .header .subhead {
                      font-size: 14px;
                      opacity: 0.9;
                      margin: 0;
                    }
                    .badge-container {
                      margin-top: 12px;
                    }
                    .status-badge {
                      display: inline-block;
                      background: #e6f4ea;
                      color: #137333;
                      font-weight: 700;
                      font-size: 13px;
                      padding: 4px 14px;
                      border-radius: 16px;
                      text-transform: uppercase;
                      border: 1px solid #ceead6;
                    }
                    .content {
                      padding: 24px;
                    }
                    .grid {
                      display: grid;
                      grid-template-columns: 1fr 1fr;
                      gap: 16px;
                      margin-bottom: 20px;
                    }
                    .item {
                      background: #f8f9fa;
                      border: 1px solid #e9ecef;
                      border-radius: 8px;
                      padding: 12px 16px;
                    }
                    .item .label {
                      font-size: 12px;
                      color: #656d76;
                      margin-bottom: 4px;
                      font-weight: 500;
                    }
                    .item .value {
                      font-size: 16px;
                      font-weight: 600;
                      color: #1f2328;
                    }
                    .price-highlight {
                      grid-column: span 2;
                      background: #edf7ed;
                      border: 1px solid #c8e6c9;
                      border-radius: 8px;
                      padding: 16px;
                      text-align: center;
                    }
                    .price-highlight .label {
                      font-size: 13px;
                      color: #2e7d32;
                      font-weight: 600;
                    }
                    .price-highlight .value {
                      font-size: 28px;
                      font-weight: 700;
                      color: #1b5e20;
                      margin-top: 4px;
                    }
                    .footer {
                      background: #f6f8fa;
                      border-top: 1px solid #d0d7de;
                      padding: 16px 24px;
                      font-size: 12px;
                      color: #57606a;
                      line-height: 1.5;
                    }
                    .footer p {
                      margin: 4px 0;
                    }
                  </style>
                </head>
                <body>
                  <div class="parchi-card">
                    <div class="header">
                      <h1>सौदा पर्ची / Sauda Parchi</h1>
                      <p class="subhead">recorded by G-One from the call</p>
                      <div class="badge-container">
                        <span class="status-badge">%s</span>
                      </div>
                    </div>
                    <div class="content">
                      <div class="grid">
                        <div class="price-highlight">
                          <div class="label">तय भाव / Agreed Price (per %s)</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">फसल / Commodity</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">किस्म / Variety</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">मात्रा / Quantity</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">मंडी / Market</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">माल उठाव / Delivery & Pickup</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">दिनांक / Date & Time</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">किसान पुष्टि / Farmer Agreed</div>
                          <div class="value">%s</div>
                        </div>
                        <div class="item">
                          <div class="label">खरीदार पुष्टि / Buyer Agreed</div>
                          <div class="value">%s</div>
                        </div>
                      </div>
                    </div>
                    <div class="footer">
                      <p><strong>Note:</strong> %s</p>
                      <p>यह विवरण कॉल वार्तालाप के आधार पर G-One द्वारा दर्ज किया गया है। यह एक गैर-बाध्यकारी सारांश है।</p>
                    </div>
                  </div>
                </body>
                </html>
                """.formatted(
                escape(receipt.id()),
                state,
                unit,
                price,
                commodity,
                variety,
                quantity,
                market,
                pickup,
                formattedDate,
                farmerYes,
                buyerYes,
                escape(receipt.note())
        );
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
