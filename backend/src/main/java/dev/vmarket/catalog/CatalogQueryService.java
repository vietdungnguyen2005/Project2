package dev.vmarket.catalog;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CatalogQueryService {
    private final JdbcClient jdbc;
    private final CatalogCache cache;

    public CatalogQueryService(JdbcClient jdbc, CatalogCache cache) {
        this.jdbc = jdbc;
        this.cache = cache;
    }

    @Transactional(readOnly = true)
    public List<ProductView> findActiveProducts() {
        var cached = cache.read();
        if (cached != null) {
            return cached;
        }
        var products = jdbc.sql("""
                        SELECT p.sku, v.name AS vendor_name, p.name, p.category, p.description,
                               p.price_minor, p.currency, p.image_path, p.image_alt,
                               s.on_hand - s.reserved AS available_quantity
                        FROM product p
                        JOIN vendor v ON v.id = p.vendor_id
                        JOIN inventory_stock s ON s.product_id = p.id
                        WHERE p.active = TRUE AND v.active = TRUE
                        ORDER BY p.sku
                        """).query(ProductView.class).list();
        cache.write(products);
        return products;
    }

    public record ProductView(
            String sku,
            String vendorName,
            String name,
            String category,
            String description,
            int priceMinor,
            String currency,
            String imagePath,
            String imageAlt,
            int availableQuantity) {}
}
