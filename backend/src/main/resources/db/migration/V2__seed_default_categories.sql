-- Global default categories (user_id IS NULL), available to every account.
-- A fresh user therefore has something to categorise against before creating
-- any category of their own.
--
-- Colours are the Tailwind 500-weight ramp, so the frontend can render a
-- category chip straight from this value without a lookup table.

INSERT INTO categories (user_id, name, type, color, icon) VALUES
    (NULL, 'Groceries',      'EXPENSE', '#22c55e', 'shopping-cart'),
    (NULL, 'Rent',           'EXPENSE', '#ef4444', 'home'),
    (NULL, 'Transport',      'EXPENSE', '#3b82f6', 'bus'),
    (NULL, 'Utilities',      'EXPENSE', '#f59e0b', 'zap'),
    (NULL, 'Restaurants',    'EXPENSE', '#f97316', 'utensils'),
    (NULL, 'Health',         'EXPENSE', '#ec4899', 'heart-pulse'),
    (NULL, 'Education',      'EXPENSE', '#8b5cf6', 'graduation-cap'),
    (NULL, 'Entertainment',  'EXPENSE', '#a855f7', 'clapperboard'),
    (NULL, 'Subscriptions',  'EXPENSE', '#6366f1', 'repeat'),
    (NULL, 'Clothing',       'EXPENSE', '#14b8a6', 'shirt'),
    (NULL, 'Savings',        'EXPENSE', '#0ea5e9', 'piggy-bank'),
    (NULL, 'Other',          'EXPENSE', '#64748b', 'ellipsis'),
    (NULL, 'Salary',         'INCOME',  '#16a34a', 'wallet'),
    (NULL, 'Freelance',      'INCOME',  '#059669', 'laptop'),
    (NULL, 'Scholarship',    'INCOME',  '#0d9488', 'award'),
    (NULL, 'Gift',           'INCOME',  '#84cc16', 'gift'),
    (NULL, 'Other Income',   'INCOME',  '#64748b', 'ellipsis');
