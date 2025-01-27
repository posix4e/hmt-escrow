import * as React from 'react';
import Box from '@mui/material/Box';
import { useNavigate } from 'react-router-dom';
import Table from 'rc-table';
import { useTheme } from '@mui/material';

import { styled } from '@mui/material/styles';
import { useGetJobsQuery } from '../../../services/redux/api/jobApi';
import './style.css';
import { BoxContainer } from '../../Grid';
import { jobListColumns } from './columns';
import { Network } from '../../Eth/constants';
import { Backdrop } from '../../Backdrop';

export function TableDataGrid() {
  const [open, setOpen] = React.useState(false);
  const handleClose = (value: boolean) => {
    setOpen(value);
  };
  const { data, error, isLoading } = useGetJobsQuery('');
  React.useEffect(() => {
    if (isLoading) {
      handleClose(true);
    } else {
      handleClose(false);
    }
  }, [isLoading]);

  const navigate = useNavigate();
  const theme = useTheme();

  const values: any = [];
  if (!isLoading && data) {
    // eslint-disable-next-line no-unused-vars
    Object.entries(data).map(([_, value]) => {
      let valueData: any = value;
      valueData = { ...valueData, price: `${valueData.price} HMT` };

      let networksData = {};
      let network: string = '';

      if (valueData.networkId === 1) {
        network = Network.Mainnet;
      } else if (valueData.networkId === 137) {
        network = Network.Polygon;
      } else if (valueData.networkId === 4) {
        network = Network.Rinkeby;
      } else if (valueData.networkId === 'local_ganache') {
        network = Network.LocalGanache;
      }
      networksData = { ...valueData, networkId: network };

      return values.push({ ...valueData, ...networksData });
    });
  }

  const BodyRow = styled('tr')({
    '& td': {
      transition: 'all 0.1s',
    },
    '&:hover td': {
      color: theme.palette.secondary.contrastText,
      cursor: 'pointer',
    },
  });

  const components = {
    body: {
      row: BodyRow,
    },
  };

  const onRowClick = (record: any) => {
    const { id } = record;
    navigate(`/job/${id}`);
  };

  return (
    <>
      <BoxContainer>
        <Box sx={{ height: 400, width: '100%' }}>
          {data && !isLoading && (
            <Table
              columns={jobListColumns}
              rowKey={(record) => record.id}
              data={values}
              components={components}
              onRow={(record) => ({
                onClick: onRowClick.bind(null, record),
              })}
            />
          )}
          {error && <div>error</div>}
        </Box>
      </BoxContainer>
      <Backdrop open={open} handleClose={handleClose} />
    </>
  );
}
