import { zodResolver } from '@hookform/resolvers/zod'
import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { z } from 'zod'
import { Button, Field, Input, Modal, Select } from '../../components/ui'
import { useCategories, useCreateTransaction, useUpdateTransaction } from '../../lib/queries'
import { useAppDispatch } from '../../app/store'
import { toast } from '../../app/uiSlice'
import { ApiError } from '../../lib/api'
import { today } from '../../lib/format'
import type { EntryType, Transaction } from '../../lib/types'

const schema = z.object({
  type: z.enum(['EXPENSE', 'INCOME']),
  // Kept as a string through the form so the input can be empty; coerced on submit.
  // z.number() on an empty input yields NaN and an unhelpful message.
  amount: z
    .string()
    .min(1, 'Amount is required')
    .refine((value) => Number(value) > 0, 'Amount must be greater than zero')
    .refine((value) => /^\d+(\.\d{1,2})?$/.test(value), 'Use at most 2 decimal places'),
  occurredOn: z.string().min(1, 'Date is required'),
  categoryId: z.string().optional(),
  description: z.string().max(255).optional(),
  merchant: z.string().max(120).optional(),
})

type FormValues = z.infer<typeof schema>

export function TransactionFormModal({
  open,
  transaction,
  onClose,
}: {
  open: boolean
  transaction: Transaction | null
  onClose: () => void
}) {
  const dispatch = useAppDispatch()
  const createTransaction = useCreateTransaction()
  const updateTransaction = useUpdateTransaction()
  const isEdit = transaction !== null

  const {
    register,
    handleSubmit,
    watch,
    reset,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { type: 'EXPENSE', amount: '', occurredOn: today() },
  })

  // The direction drives which categories are offered, so it is watched rather than read
  // once - picking "Income" must immediately re-filter the list.
  const type = watch('type') as EntryType
  const { data: categories = [] } = useCategories(type)

  useEffect(() => {
    if (!open) return
    reset(
      transaction
        ? {
            type: transaction.type,
            amount: String(transaction.amount),
            occurredOn: transaction.occurredOn,
            categoryId: transaction.category?.id ?? '',
            description: transaction.description ?? '',
            merchant: transaction.merchant ?? '',
          }
        : { type: 'EXPENSE', amount: '', occurredOn: today(), categoryId: '', description: '', merchant: '' },
    )
  }, [open, transaction, reset])

  const onSubmit = handleSubmit(async (values) => {
    const body: Record<string, unknown> = {
      amount: Number(values.amount),
      occurredOn: values.occurredOn,
      description: values.description || undefined,
      merchant: values.merchant || undefined,
    }

    try {
      if (isEdit) {
        // type is immutable server-side, so it is never sent on an update. Clearing the
        // category needs its own flag, because a null categoryId means "unchanged".
        if (values.categoryId) body.categoryId = values.categoryId
        else if (transaction.category) body.clearCategory = true

        await updateTransaction.mutateAsync({ id: transaction.id, body })
        dispatch(toast('Transaction updated'))
      } else {
        body.type = values.type
        if (values.categoryId) body.categoryId = values.categoryId

        await createTransaction.mutateAsync(body)
        dispatch(toast('Transaction added'))
      }
      onClose()
    } catch (error) {
      if (error instanceof ApiError) {
        // Map the API's field-level messages back onto the form, so a server-side rule the
        // client does not know about still lands next to the field that broke it.
        const fields = error.fieldErrors()
        let matched = false
        for (const [field, message] of Object.entries(fields)) {
          if (field in schema.shape) {
            setError(field as keyof FormValues, { message })
            matched = true
          }
        }
        if (!matched) dispatch(toast(error.message, 'error'))
      } else {
        dispatch(toast('Something went wrong. Please try again.', 'error'))
      }
    }
  })

  return (
    <Modal open={open} title={isEdit ? 'Edit transaction' : 'Add transaction'} onClose={onClose}>
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <Field label="Type" htmlFor="type" error={errors.type?.message}>
          <Select id="type" disabled={isEdit} invalid={!!errors.type} {...register('type')}>
            <option value="EXPENSE">Expense</option>
            <option value="INCOME">Income</option>
          </Select>
        </Field>
        {isEdit && (
          <p className="-mt-2 text-xs text-slate-500">
            Type cannot be changed. Delete and re-create to switch direction.
          </p>
        )}

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Amount" htmlFor="amount" error={errors.amount?.message}>
            <Input
              id="amount"
              inputMode="decimal"
              placeholder="0.00"
              invalid={!!errors.amount}
              {...register('amount')}
            />
          </Field>
          <Field label="Date" htmlFor="occurredOn" error={errors.occurredOn?.message}>
            <Input
              id="occurredOn"
              type="date"
              // The API rejects a future date, so the picker will not offer one.
              max={today()}
              invalid={!!errors.occurredOn}
              {...register('occurredOn')}
            />
          </Field>
        </div>

        <Field label="Category" htmlFor="categoryId" error={errors.categoryId?.message}>
          <Select id="categoryId" invalid={!!errors.categoryId} {...register('categoryId')}>
            <option value="">Uncategorised</option>
            {categories.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </Select>
        </Field>

        <Field label="Description" htmlFor="description" error={errors.description?.message}>
          <Input id="description" placeholder="Weekly shop" invalid={!!errors.description} {...register('description')} />
        </Field>

        <Field label="Merchant" htmlFor="merchant" error={errors.merchant?.message}>
          <Input id="merchant" placeholder="Optional" invalid={!!errors.merchant} {...register('merchant')} />
        </Field>

        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="secondary" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" loading={isSubmitting}>
            {isEdit ? 'Save changes' : 'Add transaction'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
