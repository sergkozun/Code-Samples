package pc.dao;

import org.hibernate.SessionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import pc.entity.*;
import pc.entity.ContractAccount_;
import pc.entity.Contract_;
import pc.entity.TransactionContractAccountSum_;
import org.hibernate.Session;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Root;
import javax.persistence.criteria.Subquery;
import java.math.BigDecimal;
import java.util.*;

@Repository
public class TransactionContractAccountSumDao {

    @Autowired
    @Qualifier("sessionFactory")
    private SessionFactory sessionFactory;

    public Map<Long, BigDecimal> getSum(Calendar startDate, Calendar endDate, User user, int state) {
        Session session = sessionFactory.getCurrentSession();

        CriteriaBuilder builder = session.getCriteriaBuilder();

        CriteriaQuery<TransactionContractAccountSum> query = builder.createQuery(TransactionContractAccountSum.class);
        Root<TransactionContractAccountSum> root = query.from(TransactionContractAccountSum.class);
        query.select(root);

        Subquery<ContractAccount> subQuery = query.subquery(ContractAccount.class);
        Root<ContractAccount> subRoot = subQuery.from(ContractAccount.class);
        subQuery.select(subRoot);

        Subquery<Contract> subSubQuery = subQuery.subquery(Contract.class);
        Root<Contract> subSubRoot = subSubQuery.from(Contract.class);
        subSubQuery.select(subSubRoot);

        subSubQuery.where(
                builder.equal(subSubRoot.get(Contract_.client),user.getClient().getId()),
                builder.equal(subSubRoot.get(Contract_.id), subRoot.get(ContractAccount_.contract)),
                builder.equal(subSubRoot.get(Contract_.state), state)
        );

        subQuery.where(
                builder.equal(subRoot.get(ContractAccount_.id),root.get(TransactionContractAccountSum_.contractAccount)),
                builder.exists(subSubQuery)
                );

        if(startDate!=null)
            query.where(
                    builder.exists(subQuery),
                    builder.greaterThan(root.get(TransactionContractAccountSum_.terminalDateTime),startDate.getTime()),
                    builder.lessThan(root.get(TransactionContractAccountSum_.terminalDateTime),endDate.getTime())
            );
        else
            query.where(
                    builder.exists(subQuery),
                    builder.lessThan(root.get(TransactionContractAccountSum_.terminalDateTime),endDate.getTime())
            );

        query.multiselect(root.get(TransactionContractAccountSum_.contractAccount), builder.sum(root.get(TransactionContractAccountSum_.amountChange)));
        query.groupBy(root.get(TransactionContractAccountSum_.contractAccount));
        List<TransactionContractAccountSum> list = session.createQuery(query).getResultList();
        Map<Long, BigDecimal> result = new HashMap<>();
        for(TransactionContractAccountSum transactionContractAccountSum: list)
            result.put(transactionContractAccountSum.getContractAccount().getId(),transactionContractAccountSum.getSumAmountChange());
        return result;
    }
    
}
